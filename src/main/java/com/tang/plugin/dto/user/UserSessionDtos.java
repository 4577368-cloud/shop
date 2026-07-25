package com.tang.plugin.dto.user;

/**
 * User session DTOs (P6 security page).
 *
 * <p>Session = refresh token row. Each device/browser login creates one session.
 */
public final class UserSessionDtos {

    private UserSessionDtos() {}

    /** 会话列表项。token_hash 不返回（避免泄露）。 */
    public record SessionItem(
            Long id,
            String userAgent,
            String ip,
            java.time.Instant createdAt,
            java.time.Instant expiresAt,
            /** true=已过期但未撤销（用户未主动登出，token 自然过期）。 */
            boolean expired,
            /** true=当前请求所在会话（通过 cookie 中的 refresh token 匹配）。 */
            boolean current
    ) {}

    public record SessionListResponse(java.util.List<SessionItem> items, int total) {}

    /** 远程登出响应。 */
    public record RevokeSessionResponse(Long id, boolean revoked) {}
}
