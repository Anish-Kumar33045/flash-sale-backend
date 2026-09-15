package com.example.flashsale.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Public product view. stockQuantity is included because this is a
 * flash-sale system - buyers expect to see remaining units.
 */
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
