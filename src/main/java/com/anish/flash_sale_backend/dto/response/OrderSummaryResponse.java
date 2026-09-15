package com.example.flashsale.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Slim projection used when LISTING orders. Deliberately excludes line
 * items so we never trigger lazy loading per row (the classic N+1 problem)
 * and never ship huge payloads for list pages.
 */
public record OrderSummaryResponse(
        Long id,
        String status,
        BigDecimal totalAmount,
        Instant createdAt
) {
}
