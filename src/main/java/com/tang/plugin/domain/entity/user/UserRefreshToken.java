package com.tang.plugin.domain.entity.user;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * Refresh token record (opaque, hashed in DB). Supports multi-device sessions + revocation.
 * Table: user_refresh_token
 */
@Data
@Accessors(chain = true)
public class UserRefreshToken {
    private Long id;
    private Long userId;
    private String tokenHash;
    private Instant expiresAt;
    private Instant createdAt;
    private String userAgent;
    private String ip;
    private Integer delFlag;
}
