package com.tang.plugin.domain.entity.logistics;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 物流线路接受决策（variant 粒度）。
 *
 * <p>替代原 Next.js 本地文件存储（.data/logistics/{shop}-acceptances.json），
 * 支持多实例部署。一条记录 = 用户对某个 SKU 变体"接受"的推荐/备选线路快照。
 *
 * <p>UPSERT 语义：以 (shop_name, third_platform_sku_id) 为自然键，
 * 命中则覆盖线路字段 + 更新 accepted_at，未命中则插入。
 */
@Data
@Accessors(chain = true)
public class LogisticsAcceptDecision {
    private Long id;
    private String shopName;
    private String thirdPlatformItemId;
    private String thirdPlatformSkuId;
    private String quoteStatus;
    /** LogisticsLine JSON 序列化（推荐线路） */
    private String recommendedLineJson;
    /** LogisticsLine[] JSON 序列化（备选线路） */
    private String alternativeLinesJson;
    private Instant acceptedAt;
    private Integer delFlag;
    private Instant createdAt;
    private Instant updatedAt;
}
