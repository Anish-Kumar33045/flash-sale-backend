package com.example.flashsale.mapper;

import com.example.flashsale.dto.response.ProductResponse;
import com.example.flashsale.entity.Product;
import org.springframework.stereotype.Component;

/** Manual mapping - explicit, debuggable and dependency-free for 2 DTOs. */
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
