package com.tang.plugin.domain.dto.match;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Manual SKU-level binding request. thirdPlatformSkuId is required (P1 has no SPU-only binding).
 */
@Data
@Accessors(chain = true)
public class ManualBindingDTO {
    private String shopName;
    private String thirdPlatformItemId;
    /** Shopify variant GID — required. */
    private String thirdPlatformSkuId;
    private String tangbuyProductId;
    private String tangbuySkuId;
}
