package com.anish.flash_sale_backend.exception;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String productName, int requested, int available) {
        super("Requested %d unit(s) of '%s' but only %d available".formatted(requested, productName, available));
    }
}
