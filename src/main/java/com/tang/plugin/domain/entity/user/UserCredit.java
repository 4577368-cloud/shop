package com.tang.plugin.domain.entity.user;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 用户积分账户（一对一）。积分为整数，无小数。
 * Table: user_credits
 */
@Data
@Accessors(chain = true)
public class UserCredit {
    private Long id;
    private Long userId;
    /** 当前可用积分。 */
    private Integer balanceCredits;
    /** 累计发放积分。仅用于统计。 */
    private Integer totalGranted;
    /** 累计消耗积分。仅用于统计。 */
    private Integer totalConsumed;
    /** 累计过期积分。仅用于统计。 */
    private Integer totalExpired;
    private Instant createdAt;
    private Instant updatedAt;
}
