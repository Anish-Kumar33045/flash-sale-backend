package com.anish.flash_sale_backend.mapper;

import com.anish.flash_sale_backend.dto.response.OrderItemResponse;
import com.anish.flash_sale_backend.dto.response.OrderResponse;
import com.anish.flash_sale_backend.dto.response.OrderSummaryResponse;
import com.anish.flash_sale_backend.entity.Order;
import com.anish.flash_sale_backend.entity.OrderItem;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OrderMapper {

    public OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(this::toItemResponse)
                .toList();
        return new OrderResponse(
                order.getId(),
                order.getStatus().name(),
                order.getTotalAmount(),
                items,
                order.getCreatedAt());
    }

    public OrderSummaryResponse toSummary(Order order) {
        return new OrderSummaryResponse(
                order.getId(),
                order.getStatus().name(),
                order.getTotalAmount(),
                order.getCreatedAt());
    }

    private OrderItemResponse toItemResponse(OrderItem item) {
        return new OrderItemResponse(
                item.getProduct().getId(),
                item.getProduct().getName(),
                item.getQuantity(),
                item.getPriceAtPurchase());
    }
}
