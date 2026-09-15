package com.anish.flash_sale_backend.service;

import com.anish.flash_sale_backend.async.OrderNotificationService;
import com.anish.flash_sale_backend.dto.request.OrderItemRequest;
import com.anish.flash_sale_backend.dto.request.OrderRequest;
import com.anish.flash_sale_backend.dto.response.OrderResponse;
import com.anish.flash_sale_backend.dto.response.OrderSummaryResponse;
import com.anish.flash_sale_backend.dto.response.PageResponse;
import com.anish.flash_sale_backend.entity.*;
import com.anish.flash_sale_backend.entity.enums.OrderStatus;
import com.anish.flash_sale_backend.exception.*;
import com.anish.flash_sale_backend.mapper.OrderMapper;
import com.anish.flash_sale_backend.repository.OrderRepository;
import com.anish.flash_sale_backend.repository.ProductRepository;
import com.anish.flash_sale_backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final OrderMapper orderMapper;
    private final OrderNotificationService notificationService;


    public record PlaceResult(OrderResponse order, boolean replayed) {
    }

    @Transactional
    public PlaceResult placeOrder(String email, OrderRequest request, String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new InvalidOrderException("Header 'Idempotency-Key' is required");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new InvalidOrderException("Order must contain at least one item");
        }

        User user = requireUser(email);
        String fingerprint = fingerprint(request.items());

        Optional<Order> existing = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), user, fingerprint);
        }

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

    @Transactional
    public OrderResponse cancelOrder(String email, Long orderId) {

        User user = requireUser(email);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));

        if (!order.getUser().getId().equals(user.getId())) {
            throw new ResourceNotFoundException("Order", orderId);
        }

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new InvalidOrderException("Order %d is already cancelled".formatted(orderId));
        }

        order.getItems().stream()
                .sorted(Comparator.comparing(item -> item.getProduct().getId()))
                .forEach(item -> {
                    Product product = productRepository.findByIdForUpdate(item.getProduct().getId())
                            .orElseThrow(() -> new ResourceNotFoundException("Product", item.getProduct().getId()));
                    product.setStockQuantity(product.getStockQuantity() + item.getQuantity());
                });

        order.setStatus(OrderStatus.CANCELLED);

        log.info("Order {} cancelled by {}; inventory restored", orderId, user.getEmail());
        return orderMapper.toResponse(order);
    }

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

    static String fingerprint(List<OrderItemRequest> items) {

        String canonical = items.stream()
                .sorted(Comparator.comparing(OrderItemRequest::productId))
                .map(item -> item.productId() + ":" + item.quantity())
                .collect(java.util.stream.Collectors.joining("|"));
        try {

            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private void registerAfterCommitWork(Order savedOrder, Collection<Long> productIds) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        Long orderId = savedOrder.getId();
        BigDecimal totalAmount = savedOrder.getTotalAmount();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notificationService.sendOrderConfirmation(orderId, totalAmount);
            }
        });
    }

}