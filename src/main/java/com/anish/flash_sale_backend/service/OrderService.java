package com.example.flashsale.service;

import com.example.flashsale.async.OrderNotificationService;
import com.example.flashsale.config.CacheConfig;
import com.example.flashsale.dto.request.OrderItemRequest;
import com.example.flashsale.dto.request.OrderRequest;
import com.example.flashsale.dto.response.OrderResponse;
import com.example.flashsale.dto.response.OrderSummaryResponse;
import com.example.flashsale.dto.response.PageResponse;
import com.example.flashsale.entity.Order;
import com.example.flashsale.entity.OrderItem;
import com.example.flashsale.entity.OrderStatus;
import com.example.flashsale.entity.Product;
import com.example.flashsale.entity.User;
import com.example.flashsale.exception.IdempotencyConflictException;
import com.example.flashsale.exception.InsufficientStockException;
import com.example.flashsale.exception.InvalidOrderException;
import com.example.flashsale.exception.ResourceNotFoundException;
import com.example.flashsale.mapper.OrderMapper;
import com.example.flashsale.repository.OrderRepository;
import com.example.flashsale.repository.ProductRepository;
import com.example.flashsale.repository.UserRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * The heart of the project: buying limited stock safely.
 *
 * ============================ TRANSACTION BOUNDARIES ============================
 * placeOrder and cancelOrder are each ONE database transaction (@Transactional).
 *
 * placeOrder steps inside the transaction:
 *   1. resolve the buyer
 *   2. short-circuit on an existing Idempotency-Key (exact replay -> same answer)
 *   3. SELECT ... FOR UPDATE every requested product (pessimistic lock,
 *      ascending product id so concurrent multi-item orders queue in a
 *      consistent order and cannot deadlock)
 *   4. validate availability + active flag per product
 *   5. decrement stock, build items at priceAtPurchase, sum the total
 *   6. persist order (+ cascaded items)
 *   7. COMMIT - only now are stock changes visible to everyone else
 *
 * If ANY step throws, Spring rolls the whole transaction back: stock is never
 * partially deducted and no orphan order survives. This invariant is proven by
 * the rollback integration test.
 *
 * ============================ WHY PESSIMISTIC LOCKING ===========================
 * Flash sales funnel many buyers onto ONE hot row at the same moment.
 * Optimistic locking would fail nearly every request with version conflicts;
 * pessimistic FOR UPDATE makes contenders QUEUE briefly and succeed as stock
 * allows - exactly the desired flash-sale behavior. Optimistic locking is used
 * where contention is rare (admin edits), see ProductService/Product.version.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final OrderMapper orderMapper;
    private final OrderNotificationService notificationService;
    private final CacheManager cacheManager;

    /** replayed=true means this exact request was already processed earlier. */
    public record PlaceResult(OrderResponse order, boolean replayed) {
    }

    /**
     * Synchronous critical section. Runs in a single transaction; the async
     * confirmation is only registered to fire AFTER commit (see below).
     */

    @Transactional
    public PlaceResult placeOrder(String email, OrderRequest request, String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new InvalidOrderException("Header 'Idempotency-Key' is required");
        }
        if (request.items() == null || request.items().isEmpty()) {
            // Bean Validation already blocks this at the API layer; repeated
            // here so the service contract holds even for non-HTTP callers.
            throw new InvalidOrderException("Order must contain at least one item");
        }

        User user = requireUser(email);
        String fingerprint = fingerprint(request.items());

        // ---- Idempotency check -------------------------------------------------
        // Network retries can resubmit a request the server already completed.
        // The unique index on idempotency_key is the final backstop if two
        // identical retries race past this check simultaneously.
        Optional<Order> existing = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), user, fingerprint);
        }

        // Merge duplicate lines for the same product before touching the DB.
        Map<Long, Integer> demands = new TreeMap<>();
        request.items().forEach(item ->
                demands.merge(item.productId(), item.quantity(), Integer::sum));

        Order order = new Order();
        order.setUser(user);
        order.setStatus(OrderStatus.CONFIRMED);
        order.setIdempotencyKey(idempotencyKey);
        order.setRequestFingerprint(fingerprint);

        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> demand : demands.entrySet()) {
            Product product = productRepository.findByIdForUpdate(demand.getKey())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", demand.getKey()));

            if (!product.isActive()) {
                throw new InvalidOrderException(
                        "Product '%s' is not available for purchase".formatted(product.getName()));
            }

            if (product.getStockQuantity() < demand.getValue()) {
                // Thrown while holding the row lock: "available" here is exact.
                log.info("Stock conflict on product {}: wanted {}, had {}",
                        product.getId(), demand.getValue(), product.getStockQuantity());
                throw new InsufficientStockException(product.getName(),
                        demand.getValue(), product.getStockQuantity());
            }

            product.setStockQuantity(product.getStockQuantity() - demand.getValue());

            OrderItem item = new OrderItem();
            item.setProduct(product);
            item.setQuantity(demand.getValue());
            item.setPriceAtPurchase(product.getPrice()); // freeze history
            order.addItem(item);

            total = total.add(product.getPrice().multiply(BigDecimal.valueOf(demand.getValue())));
        }
        order.setTotalAmount(total);

        Order saved = orderRepository.save(order);
        log.info("Order {} created for {} ({} line(s), total {})",
                saved.getId(), email, order.getItems().size(), total);

        registerAfterCommitWork(saved, demands.keySet());
        return new PlaceResult(orderMapper.toResponse(saved), false);
    }

    /**
     * Cancellation restocks every line. Allowed while PENDING/CONFIRMED;
     * cancelling twice is rejected as INVALID_ORDER.
     */
    @Transactional
    public OrderResponse cancelOrder(String email, Long orderId) {

        User user = requireUser(email);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));

        if (!order.getUser().getId().equals(user.getId())) {
            // 404 rather than 403 so we do not leak which ids exist for others.
            throw new ResourceNotFoundException("Order", orderId);
        }

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new InvalidOrderException("Order %d is already cancelled".formatted(orderId));
        }

        // Ascending product id keeps lock ordering consistent with placeOrder.
        order.getItems().stream()
                .sorted(Comparator.comparing(item -> item.getProduct().getId()))
                .forEach(item -> {
                    Product product = productRepository.findByIdForUpdate(item.getProduct().getId())
                            .orElseThrow(() -> new ResourceNotFoundException("Product", item.getProduct().getId()));
                    product.setStockQuantity(product.getStockQuantity() + item.getQuantity());
                });

        order.setStatus(OrderStatus.CANCELLED);
        // Direct eviction is fine here: evicting before a hypothetical rollback
        // only costs one extra cache miss, never stale data.
        evictProductsFromCache(order.getItems().stream()
                .map(item -> item.getProduct().getId())
                .toList());
        log.info("Order {} cancelled by {}; inventory restored", orderId, user.getEmail());
        return orderMapper.toResponse(order);
    }

    /** Own orders, newest first, paginated summaries (no lazy-item access). */
    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> getMyOrders(String email, Pageable pageable) {
        User user = requireUser(email);
        Page<Order> page = orderRepository.findByUserId(user.getId(), pageable);
        return PageResponse.from(page, orderMapper::toSummary);
    }

    @Transactional(readOnly = true)
    public OrderResponse getMyOrder(String email, Long orderId) {
        User user = requireUser(email);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        if (!order.getUser().getId().equals(user.getId())) {
            throw new ResourceNotFoundException("Order", orderId);
        }
        return orderMapper.toResponse(order);
    }

    /** Admin view over all orders, optional status filter. */
    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> getAllOrders(OrderStatus status, Pageable pageable) {
        Page<Order> page = status == null
                ? orderRepository.findAll(pageable)
                : orderRepository.findByStatus(status, pageable);
        return PageResponse.from(page, orderMapper::toSummary);
    }

    private PlaceResult replayOrConflict(Order prior, User user, String fingerprint) {
        boolean sameBuyerAndCart = prior.getUser().getId().equals(user.getId())
                && prior.getRequestFingerprint().equals(fingerprint);
        if (!sameBuyerAndCart) {
            throw new IdempotencyConflictException(prior.getIdempotencyKey());
        }
        log.info("Idempotent replay: returning existing order {}", prior.getId());
        return new PlaceResult(orderMapper.toResponse(prior), true);
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User", email));
    }

    /**
     * SHA-256 of the canonicalized cart ("productId:qty" sorted, joined by |).
     * Lets us detect a reused key with a DIFFERENT cart without storing the
     * whole request body.
     */
    static String fingerprint(List<OrderItemRequest> items) {

        /*reuest contains :
        Product 10 → quantity 2
        Product 20 → quantity 1
         */
        String canonical = items.stream()
                .sorted(Comparator.comparing(OrderItemRequest::productId))
                .map(item -> item.productId() + ":" + item.quantity()) // 10:2 20:1
                .collect(java.util.stream.Collectors.joining("|")); // 10:2|20:1
        try {
            // converting 10:2|20:1 to SHA-256 hash.
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Work that must observe COMMITTED data, registered to fire only after
     * the transaction succeeds:
     *  1. async order confirmation (notification failure must never fail an order)
     *  2. cache eviction of purchased products, so buyers never see stale
     *     stock after their own purchase (evicting inside a transaction that
     *     later rolls back would wrongly drop fresh cache entries)
     */
    private void registerAfterCommitWork(Order savedOrder, Collection<Long> productIds) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return; // e.g. plain unit tests without a real transaction
        }
        Long orderId = savedOrder.getId();
        BigDecimal totalAmount = savedOrder.getTotalAmount();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notificationService.sendOrderConfirmation(orderId, totalAmount);
                evictProductsFromCache(productIds);
            }
        });
    }

    /** Programmatic cache eviction (complements the @CacheEvict annotations in ProductService). */
    private void evictProductsFromCache(Collection<Long> productIds) {
        Cache cache = cacheManager.getCache(CacheConfig.PRODUCT_CACHE);
        if (cache != null) {
            productIds.forEach(cache::evict);
            log.debug("Evicted {} product(s) from '{}' cache", productIds.size(), CacheConfig.PRODUCT_CACHE);
        }
    }
}