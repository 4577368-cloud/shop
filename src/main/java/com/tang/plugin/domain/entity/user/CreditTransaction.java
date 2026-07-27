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
    /** 幂等键（如 marketing_api 的 cacheKey），用于防止重复扣费。 */
    private String idempotencyKey;
    /**
     * 扣减桶（§4.1）：welcome(免费) / promo(促销) / subscription(月订) / credit_pack(加购) / manual。
     * 记录主扣减桶（第一个被扣的桶）。跨桶完整路径见 {@link #bucketsJson}。
     */
    private String bucket;
    /**
     * 跨桶扣减路径 JSON（§4.3 ⚠️1 fix）。
     * 格式：[{"bucket":"welcome","amount":12},{"bucket":"subscription","amount":2}]
     * 仅 consume 类型且跨桶时有值；单桶扣减时为 null（bucket 字段已足够）。
     */
    private String bucketsJson;
    /**
     * 上游实际消耗 U（pipispy {@code consumed_credits}）。用户实扣 = U × 2。
     * 仅 marketing_api 消耗写入；发放/过期为 NULL。
     */
    private Integer upstreamCredits;
    private Instant createdAt;
}
