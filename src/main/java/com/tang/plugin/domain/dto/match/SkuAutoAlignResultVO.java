package com.tang.plugin.domain.dto.match;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * S1-b1 result of auto-aligning a bound product's Shopify variants to the 1688 offer's SKU matrix.
 * Reports the offer used, per-variant outcomes, and how many variants were confidently bound.
 */
@Data
@Accessors(chain = true)
public class SkuAutoAlignResultVO {
    private String thirdPlatformItemId;
    /** 1688 offer id whose SKU matrix was aligned against (from the product-level binding). */
    private String offerId;
    private int totalVariants;
    private int matchedCount;
    private List<SkuAutoAlignItemVO> items;
}
