package com.anish.flash_sale_backend.async;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
public class OrderNotificationService {

    @Async("orderEventExecutor")
    public void sendOrderConfirmation(Long orderId, BigDecimal totalAmount) {
        try {
            // Stand-in for SMTP/push integration - deliberately out of scope.
            log.info("[ASYNC] Order confirmation dispatched for order {} (total {})", orderId, totalAmount);
        } catch (Exception e) {
            log.error("Failed to dispatch confirmation for order {}", orderId, e);
        }
    }
}
