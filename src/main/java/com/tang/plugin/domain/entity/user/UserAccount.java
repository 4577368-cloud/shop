package com.tang.plugin.domain.entity.user;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 用户余额账户（一对一）。金额以分为单位存储（BIGINT），避免浮点误差。
 * Table: user_accounts
 */
@Data
@Accessors(chain = true)
public class UserAccount {
    private Long id;
    private Long userId;
    /** 当前余额（分）。正数表示可用余额。 */
    private Long balanceCny;
    /** 累计充值金额（分）。仅用于统计，不会等于 balance。 */
    private Long totalRecharged;
    /** 累计消费金额（分）。仅用于统计。 */
    private Long totalConsumed;
    /** 累计退款金额（分）。仅用于统计。 */
    private Long totalRefunded;
    private Instant createdAt;
    private Instant updatedAt;
}
