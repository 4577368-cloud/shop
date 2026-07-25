package com.tang.plugin.dto.auth;

/**
 * Auth request/response DTOs (Java records).
 */
public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(String email, String password, String name) {}

    public record LoginRequest(String email, String password) {}

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    public record RefreshRequest(String refreshToken) {}

    public record UserResponse(
            Long id,
            String email,
            String name,
            String avatarUrl,
            String locale,
            String timezone,
            String currency,
            String aiResponseLanguage,
            String status
    ) {}

    public record AuthResponse(UserResponse user) {}

    public record RefreshResponse(String accessToken) {}
}
