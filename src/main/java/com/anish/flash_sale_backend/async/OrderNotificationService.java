package com.example.flashsale.async;

import java.math.BigDecimal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Non-critical post-order work, executed on the "orderEventExecutor" pool.
 *
 * SYNCHRONOUS (inside the buyer's transaction): validate cart, lock rows,
 * decrement inventory, persist order + items. If any of this fails the
 * buyer must see the error immediately and nothing may be persisted.
 *
 * ASYNCHRONOUS (after commit): confirmation notification / event logging.
 * The response is already on its way; a notification failure must never
 * fail an order, which is why exceptions are swallowed and logged here.
 */
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
