package com.anish.flash_sale_backend.dto.request;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

//Used for both create (POST) and full update (PUT) of a product.

public record ProductRequest(
        @NotBlank(message = "Product name is required")
        @Size(max = 200, message = "Product name must be at most 200 characters")
        String name,

        @Size(max = 2000, message = "Description must be at most 2000 characters")
        String description,

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0.01", message = "Price must be positive")
        @Digits(integer = 10, fraction = 2, message = "Price supports at most 10 integer digits and 2 decimals")
        BigDecimal price,

        @NotBlank(message = "Category is required")
        @Size(max = 80, message = "Category must be at most 80 characters")
        String category,

        @NotNull(message = "stockQuantity is required")
        @Min(value = 0, message = "Stock cannot be negative")
        Integer stockQuantity,

        Boolean active
) {
}
