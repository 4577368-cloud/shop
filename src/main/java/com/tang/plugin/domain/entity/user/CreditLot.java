package com.tang.plugin.domain.entity.user;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 积分批次（支持过期，FIFO 消耗）。
 * Table: credit_lots
 *
 * <p>每次发放写一条 lot 记录；消耗时按 created_at ASC 顺序扣减未过期批次。
 * source_type: subscription / credit_pack / promo / manual
 */
@Data
@Accessors(chain = true)
public class CreditLot {
    private Long id;
    private Long userId;
    /** 来源类型：subscription / credit_pack / promo / manual */
    private String sourceType;
    /** 来源 ID（订阅记录 ID / 积分包订单 ID / null）。 */
    private Long sourceId;
    /** 原始发放额度。 */
    private Integer amountGranted;
    /** 已消耗额度。 */
    private Integer amountConsumed;
    /** 已过期额度。 */
    private Integer amountExpired;
    /** 过期时间（null = 永不过期）。 */
    private Instant expiresAt;
    private Instant createdAt;
}
