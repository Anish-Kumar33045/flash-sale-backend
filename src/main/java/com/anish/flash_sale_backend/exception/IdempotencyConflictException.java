package com.anish.flash_sale_backend.exception;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String key) {
        super("Idempotency-Key '%s' was already used for a different request".formatted(key));
    }
}
