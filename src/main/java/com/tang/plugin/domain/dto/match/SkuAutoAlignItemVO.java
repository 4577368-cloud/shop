package com.tang.plugin.domain.dto.match;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * S1-b1: the auto-alignment outcome for a single Shopify variant — which 1688 SKU it was matched to
 * (or {@code matched=false} when no confident match was found and the variant was left untouched).
 */
@Data
@Accessors(chain = true)
public class SkuAutoAlignItemVO {
    private String thirdPlatformSkuId;
    private String optionLabel;
    private boolean matched;
    /** Matched 1688 skuId (null when unmatched). */
    private String tangbuySkuId;
    /** Matched 1688 spec label, e.g. "Red / M" (null when unmatched). */
    private String tangbuySkuSpec;
    /** Overlap score 0..1. */
    private BigDecimal score;
}
