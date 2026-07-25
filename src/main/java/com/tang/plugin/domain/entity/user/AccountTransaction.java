package com.tang.plugin.domain.entity.user;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 余额变动流水。每次充值/消费/退款/调整都插入一条记录。
 * Table: account_transactions
 */
@Data
@Accessors(chain = true)
public class AccountTransaction {
    private Long id;
    private Long userId;
    /** 流水类型：recharge(充值) / consume(消费) / refund(退款) / adjust(人工调整) */
    private String type;
    /** 变动金额（分）。正数=入账，负数=出账。 */
    private Long amountCny;
    /** 变动前余额（分）。 */
    private Long balanceBefore;
    /** 变动后余额（分）。 */
    private Long balanceAfter;
    /** 关联业务类型：order / recharge / manual / subscription / credit_pack */
    private String refType;
    /** 关联业务 ID（如 Shopify 订单号、充值订单号）。 */
    private String refId;
    /** 备注（最多 255 字符）。 */
    private String remark;
    private Instant createdAt;
}
