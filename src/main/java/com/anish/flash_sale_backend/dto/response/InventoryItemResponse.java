package com.anish.flash_sale_backend.dto.response;

public record InventoryItemResponse(
        Long productId,
        String name,
        String category,
        int stockQuantity,
        boolean active,
        boolean lowStock
) {
}
