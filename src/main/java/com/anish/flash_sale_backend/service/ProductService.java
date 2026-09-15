package com.example.flashsale.service;

import com.example.flashsale.config.CacheConfig;
import com.example.flashsale.dto.request.InventoryRequest;
import com.example.flashsale.dto.request.ProductRequest;
import com.example.flashsale.dto.response.InventoryItemResponse;
import com.example.flashsale.dto.response.MessageResponse;
import com.example.flashsale.dto.response.PageResponse;
import com.example.flashsale.dto.response.ProductResponse;
import com.example.flashsale.entity.Product;
import com.example.flashsale.exception.ResourceNotFoundException;
import com.example.flashsale.mapper.ProductMapper;
import com.example.flashsale.repository.ProductRepository;
import com.example.flashsale.repository.ProductSpecifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Catalog management.
 *
 * Caching strategy (cache-aside):
 *  - getProductById  -> @Cacheable: Redis first, DB only on a miss
 *  - list/search     -> NOT cached: filters+sorting are too dynamic and the
 *                       DB answers them quickly via indexes
 *  - every admin mutation -> @CacheEvict so stale data never outlives the
 *                       request that changed it (TTL 10m is only a backstop)
 */

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> getProducts(String search, String category, Boolean active,
                                                     Pageable pageable) {
        Page<Product> page = productRepository.findAll(
                ProductSpecifications.withFilters(normalize(search), normalize(category), active), pageable);
        return PageResponse.from(page, productMapper::toResponse);
    }

    /**
     * Cache hit: returned from Redis without touching PostgreSQL.
     * Cache miss: load from PostgreSQL, then store in Redis for next time.
     */
    @Cacheable(cacheNames = CacheConfig.PRODUCT_CACHE, key = "#id")
    @Transactional(readOnly = true)
    public ProductResponse getProduct(Long id) {
        Product product = findProduct(id);
        log.info("Cache miss - loaded product {} from database", id);
        return productMapper.toResponse(product);
    }

    @Transactional
    public ProductResponse createProduct(ProductRequest request) {
        Product product = new Product();
        applyRequest(product, request);
        if (request.active() == null) {
            product.setActive(true);
        }
        Product saved = productRepository.save(product);
        log.info("Admin created product {} (id={}, stock={})", saved.getName(), saved.getId(), saved.getStockQuantity());
        return productMapper.toResponse(saved);
    }

    /**
     * Plain read-modify-write: no explicit lock taken. If two admins edit
     * concurrently, the SECOND commit fails with ObjectOptimisticLockingFailureException
     * (HTTP 409) because Product.version moved underneath it. That is
     * OPTIMISTIC locking doing its job for low-contention admin work.
     */
    @CacheEvict(cacheNames = CacheConfig.PRODUCT_CACHE, key = "#id")
    @Transactional
    public ProductResponse updateProduct(Long id, ProductRequest request) {
        Product product = findProduct(id);
        applyRequest(product, request);
        if (request.active() != null) {
            product.setActive(request.active());
        }
        log.info("Admin updated product {}", id);
        return productMapper.toResponse(productRepository.save(product));
    }

    /** Inventory adjustments are rare and admin-driven -> optimistic locking fits. */
    @CacheEvict(cacheNames = CacheConfig.PRODUCT_CACHE, key = "#id")
    @Transactional
    public ProductResponse updateInventory(Long id, InventoryRequest request) {
        Product product = findProduct(id);
        product.setStockQuantity(request.stockQuantity());
        log.info("Admin set stock of product {} to {}", id, request.stockQuantity());
        return productMapper.toResponse(productRepository.save(product));
    }

    /** Soft delete: keep historical orders intact, hide from the catalog. */
    @CacheEvict(cacheNames = CacheConfig.PRODUCT_CACHE, key = "#id")
    @Transactional
    public MessageResponse deactivateProduct(Long id) {
        Product product = findProduct(id);
        product.setActive(false);
        productRepository.save(product);
        log.info("Admin deactivated product {}", id);
        return new MessageResponse("Product deactivated");
    }

    @Transactional(readOnly = true)
    public PageResponse<InventoryItemResponse> getInventory(int lowStockThreshold, Pageable pageable) {
        Page<Product> page = productRepository.findAll(ProductSpecifications.withFilters(null, null, null), pageable);
        return PageResponse.from(page, product -> new InventoryItemResponse(
                product.getId(),
                product.getName(),
                product.getCategory(),
                product.getStockQuantity(),
                product.isActive(),
                product.getStockQuantity() <= lowStockThreshold));
    }

    private void applyRequest(Product product, ProductRequest request) {
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setCategory(request.category());
        product.setStockQuantity(request.stockQuantity());
    }

    private Product findProduct(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
