package com.tang.plugin.domain.dto.match;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * S1-a SKU overview aggregated per product: only products that have at least one ACTIVE binding
 * (i.e. confirmed on the selection page) are returned, expanded into their Shopify variants.
 */
@Data
@Accessors(chain = true)
public class SkuProductOverviewVO {
    private String thirdPlatformItemId;
    private String title;
    private String imageUrl;
    /** Product-level Tangbuy offer id (from any bound variant); used by SKU picker. */
    private String tangbuyProductId;
    /** Tangbuy detail URL for itemGet; resolved from bound variants' audit reason. */
    private String detailUrl;
    /** Shopify shop currency for listing-price display (e.g. USD). */
    private String currency;
    private List<SkuVariantVO> variants;
}
