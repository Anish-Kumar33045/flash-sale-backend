package com.anish.flash_sale_backend.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        Map<String, String> fieldErrors
) {

    public static ErrorResponse of(int status, String error, String message) {
        return new ErrorResponse(Instant.now(), status, error, message, null);
    }

    public static ErrorResponse withFieldErrors(int status, String message, Map<String, String> fieldErrors) {
        return new ErrorResponse(Instant.now(), status, "VALIDATION_ERROR", message, fieldErrors);
    }
}
