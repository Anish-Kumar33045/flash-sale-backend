package com.anish.flash_sale_backend.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderSummaryResponse(
        Long id,
        String status,
        BigDecimal totalAmount,
        Instant createdAt
) {
}
