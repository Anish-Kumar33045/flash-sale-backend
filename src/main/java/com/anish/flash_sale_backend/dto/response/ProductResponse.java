package com.anish.flash_sale_backend.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(
        Long id,
        String name,
        String description,
        BigDecimal price,
        int stockQuantity,
        boolean active,
        String category,
        Instant createdAt,
        Instant updatedAt
) {
}
