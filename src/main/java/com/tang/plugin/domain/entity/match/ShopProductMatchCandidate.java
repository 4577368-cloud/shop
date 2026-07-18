package com.tang.plugin.domain.entity.match;

import com.tang.plugin.enums.match.MatchSource;
import com.tang.plugin.enums.match.MatchStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * SKU-level candidate mapping between a platform SKU mirror and a Tangbuy SKU.
 * P1 only supports SKU-level candidates: {@code thirdPlatformSkuId} and {@code tangbuySkuId}
 * are both required, so the natural key has no nullable columns. Tangbuy ids are opaque refs (no FK).
 */
@Data
@Accessors(chain = true)
public class ShopProductMatchCandidate {
    private Long id;
    private String shopName;
    private String shopType;
    private String thirdPlatformItemId;
    /** Shopify variant GID — required (SKU-level only). */
    private String thirdPlatformSkuId;
    private String tangbuyProductId;
    /** Tangbuy SKU reference — required (SKU-level only). */
    private String tangbuySkuId;
    private MatchSource matchSource;
    /** Reserved for RULE/IMAGE scoring; null for MANUAL. */
    private BigDecimal matchScore;
    private String matchReason;
    private MatchStatus status;
    private Integer delFlag;
    private Instant createdAt;
    private Instant updatedAt;
}
