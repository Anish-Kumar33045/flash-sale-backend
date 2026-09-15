package com.anish.flash_sale_backend.mapper;

import com.anish.flash_sale_backend.dto.response.ProductResponse;
import com.anish.flash_sale_backend.entity.Product;
import org.springframework.stereotype.Component;

@Component
public class ProductMapper {

    public ProductResponse toResponse(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStockQuantity(),
                product.isActive(),
                product.getCategory(),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }
}
