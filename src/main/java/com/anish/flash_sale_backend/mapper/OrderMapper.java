package com.example.flashsale.mapper;

import com.example.flashsale.dto.response.OrderItemResponse;
import com.example.flashsale.dto.response.OrderResponse;
import com.example.flashsale.dto.response.OrderSummaryResponse;
import com.example.flashsale.entity.Order;
import com.example.flashsale.entity.OrderItem;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class OrderMapper {

    /**
     * Full detail view including line items. Touches lazy collections -
     * only call inside an open transaction (see OrderService).
     */
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

    /** List view without touching lazy item collections (N+1 avoidance). */
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
