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

    public record RefreshResponse(String accessToken, String refreshToken) {}

    // ===== Forgot / Reset password (P6) =====

    /**
     * 忘记密码请求。
     * 无论 email 是否存在都返回 200（防止用户枚举），
     * 但只有 email 存在时才生成 reset token。
     */
    public record ForgotPasswordRequest(String email) {}

    /**
     * 忘记密码响应。
     *
     * @param resetToken 开发阶段直接返回 token 供前端跳转 reset 页面；
     *                   生产阶段应改为发邮件，此字段为 null
     * @param expiresAt  过期时间（ISO-8601）
     */
    public record ForgotPasswordResponse(String resetToken, java.time.Instant expiresAt) {}

    /**
     * 重置密码请求。
     *
     * @param resetToken forgot-password 返回的 token
     * @param newPassword 新密码（8-128 字符，至少 1 字母 + 1 数字）
     */
    public record ResetPasswordRequest(String resetToken, String newPassword) {}

    /** 重置密码响应。成功后用户需重新登录（所有 refresh token 已吊销）。 */
    public record ResetPasswordResponse(boolean success) {}
}
