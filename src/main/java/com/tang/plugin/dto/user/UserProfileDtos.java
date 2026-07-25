package com.tang.plugin.dto.user;

/**
 * User profile DTOs (P6).
 *
 * <p>Profile fields: name / avatarUrl / locale / timezone / currency / aiResponseLanguage.
 * Email is read-only (changing email requires a separate verification flow).
 */
public final class UserProfileDtos {

    private UserProfileDtos() {}

    /** 个人资料响应（GET /user/profile）。 */
    public record ProfileResponse(
            Long id,
            String email,
            String name,
            String avatarUrl,
            String locale,
            String timezone,
            String currency,
            String aiResponseLanguage,
            String status,
            java.time.Instant createdAt,
            java.time.Instant lastLoginAt
    ) {}

    /**
     * 更新个人资料请求（PUT /user/profile）。
     * 所有字段可选；null 或空字符串表示不更新（保持原值）。
     * email / status / createdAt / lastLoginAt 不可通过此接口修改。
     */
    public record UpdateProfileRequest(
            String name,
            String avatarUrl,
            String locale,
            String timezone,
            String currency,
            String aiResponseLanguage
    ) {}
}
