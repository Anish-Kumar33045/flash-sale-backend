package com.example.flashsale.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Full order detail including line items at historical prices. */
public record OrderResponse(
        Long id,
        String status,
        BigDecimal totalAmount,
        List<OrderItemResponse> items,
        Instant createdAt
) {
}
