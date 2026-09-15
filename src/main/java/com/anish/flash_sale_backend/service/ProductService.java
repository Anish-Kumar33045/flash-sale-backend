package com.anish.flash_sale_backend.service;

import com.anish.flash_sale_backend.dto.request.InventoryRequest;
import com.anish.flash_sale_backend.dto.request.ProductRequest;
import com.anish.flash_sale_backend.dto.response.InventoryItemResponse;
import com.anish.flash_sale_backend.dto.response.MessageResponse;
import com.anish.flash_sale_backend.dto.response.PageResponse;
import com.anish.flash_sale_backend.dto.response.ProductResponse;
import com.anish.flash_sale_backend.entity.Product;
import com.anish.flash_sale_backend.exception.ResourceNotFoundException;
import com.anish.flash_sale_backend.mapper.ProductMapper;
import com.anish.flash_sale_backend.repository.ProductRepository;
import com.anish.flash_sale_backend.repository.ProductSpecifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;


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

    @Transactional
    public ProductResponse updateInventory(Long id, InventoryRequest request) {
        Product product = findProduct(id);
        product.setStockQuantity(request.stockQuantity());
        log.info("Admin set stock of product {} to {}", id, request.stockQuantity());
        return productMapper.toResponse(productRepository.save(product));
    }

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
