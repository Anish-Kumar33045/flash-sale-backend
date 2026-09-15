package com.example.flashsale.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Admin inventory update: set absolute stock (never negative). */
public record InventoryRequest(
        @NotNull(message = "stockQuantity is required")
        @Min(value = 0, message = "Stock cannot be negative")
        Integer stockQuantity
) {
}