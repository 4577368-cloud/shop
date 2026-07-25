package com.tang.plugin.domain.entity.user;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 积分变动流水。每次发放/消耗/过期/调整都插入一条记录。
 * Table: credit_transactions
 */
@Data
@Accessors(chain = true)
public class CreditTransaction {
    private Long id;
    private Long userId;
    /** 流水类型：grant(发放) / consume(消耗) / expire(过期) / adjust(人工调整) */
    private String type;
    /** 变动积分。正数=入账，负数=出账。 */
    private Integer amount;
    /** 变动前余额。 */
    private Integer balanceBefore;
    /** 变动后余额。 */
    private Integer balanceAfter;
    /** 关联业务类型：subscription / credit_pack / marketing_api / manual */
    private String refType;
    /** 关联业务 ID（如订阅 ID、积分包订单 ID）。 */
    private String refId;
    /** marketing_api 时的具体接口名（如 ad-products/search）。 */
    private String endpoint;
    /** 备注（最多 255 字符）。 */
    private String remark;
    private Instant createdAt;
}
