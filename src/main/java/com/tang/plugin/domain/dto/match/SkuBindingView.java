package com.tang.plugin.domain.dto.match;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Read-only projection for order-side lookup: platform variant → Tangbuy target.
 */
@Data
@Accessors(chain = true)
public class SkuBindingView {
    private String shopName;
    private String thirdPlatformItemId;
    private String thirdPlatformSkuId;
    private String tangbuyProductId;
    private String tangbuySkuId;
}
