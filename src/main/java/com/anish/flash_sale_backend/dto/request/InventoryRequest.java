package com.anish.flash_sale_backend.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record InventoryRequest(
        @NotNull(message = "stockQuantity is required")
        @Min(value = 0, message = "Stock cannot be negative")
        Integer stockQuantity
) {
}