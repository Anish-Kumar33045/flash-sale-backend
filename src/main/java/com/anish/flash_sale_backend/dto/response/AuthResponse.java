package com.example.flashsale.dto.response;

/**
 * Returned by register and login. Carries everything a client needs to
 * start calling authenticated endpoints.
 */
public record AuthResponse(
        String token,
        String tokenType,
        Long userId,
        String name,
        String email,
        String role
) {
}
