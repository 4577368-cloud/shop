package com.tang.plugin.domain.dto.match;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * S1-a: one Shopify variant of a bound product, with its current binding state ({@code bound} is null
 * when the variant is not yet bound — the common case since A3-2b binds only the default variant).
 * {@code optionLabel} always carries a non-blank, human-readable spec name (see service fallback).
 */
@Data
@Accessors(chain = true)
public class SkuVariantVO {
    private String thirdPlatformSkuId;
    private String sku;
    private String optionLabel;
    private BigDecimal price;
    private String imageUrl;
    private SkuVariantBindingVO bound;
}
