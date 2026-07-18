package com.tang.plugin.domain.entity.match;

import com.tang.plugin.enums.match.BindingStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * Confirmed binding at SKU granularity. Anchor key: (shop_name, third_platform_sku_id).
 * This is the order-side join target; Tangbuy ids are opaque external references.
 */
@Data
@Accessors(chain = true)
public class ShopProductBinding {
    private Long id;
    private String shopName;
    private String shopType;
    private String thirdPlatformItemId;
    /** Shopify variant GID — required, order-side join key. */
    private String thirdPlatformSkuId;
    private String tangbuyProductId;
    private String tangbuySkuId;
    /** MANUAL or FROM_CANDIDATE. */
    private String bindSource;
    private Long candidateId;
    private BindingStatus bindStatus;
    private Integer delFlag;
    private Instant createdAt;
    private Instant updatedAt;
}
