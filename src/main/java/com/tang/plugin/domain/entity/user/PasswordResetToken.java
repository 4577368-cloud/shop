package com.tang.plugin.domain.entity.user;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 密码重置令牌（一次性，短 TTL）。
 * Table: user_password_reset_tokens
 *
 * <p>语义不同于 refresh token：
 * <ul>
 *   <li>一次性：使用后标记 used_at，不可再用</li>
 *   <li>短 TTL：默认 30 分钟</li>
 *   <li>绑定 email 上下文：forgot-password 时记录 IP/UA 供审计</li>
 * </ul>
 */
@Data
@Accessors(chain = true)
public class PasswordResetToken {
    private Long id;
    private Long userId;
    /** SHA-256 hash of raw token. */
    private String tokenHash;
    private Instant expiresAt;
    /** null = 未使用；非空 = 已使用时间。 */
    private Instant usedAt;
    private String ip;
    private String userAgent;
    private Instant createdAt;
}
