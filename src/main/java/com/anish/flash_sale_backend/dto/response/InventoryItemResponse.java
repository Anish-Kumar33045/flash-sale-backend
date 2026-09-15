package com.example.flashsale.dto.response;

/**
 * Admin inventory view: what is stocked, where it stands, and whether it
 * dipped below the configured low-stock threshold.
 */
public record InventoryItemResponse(
        Long productId,
        String name,
        String category,
        int stockQuantity,
        boolean active,
        boolean lowStock
) {
}
