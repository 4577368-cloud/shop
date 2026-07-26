package com.tang.plugin.domain.entity.user;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 欢迎分领取记录（§4.2）。user_id 为主键，保证每人仅领取一次。
 * Table: user_welcome_claims
 */
@Data
@Accessors(chain = true)
public class UserWelcomeClaim {
    private Long userId;
    private Instant claimedAt;
}
