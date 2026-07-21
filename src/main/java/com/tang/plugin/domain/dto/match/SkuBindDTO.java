package com.tang.plugin.domain.dto.match;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Manual SKU binding from /sku-align picker. Writes an ACTIVE binding with audit metadata
 * (spec label + detail URL) encoded on the linked candidate.
 */
@Data
@Accessors(chain = true)
public class SkuBindDTO {
    private String shopName;
    private String thirdPlatformItemId;
    /** Shopify variant GID — required. */
    private String thirdPlatformSkuId;
    private String tangbuyProductId;
    private String tangbuySkuId;
    /** Human-readable spec from itemGet (e.g. "红色 / M"). */
    private String tangbuySkuSpec;
    /** Tangbuy product URL used to fetch itemGet; persisted for overview detail links. */
    private String detailUrl;
}
