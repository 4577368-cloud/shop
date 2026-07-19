package com.tang.plugin.domain.dto.match;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * S1-a: the ACTIVE binding attached to a single Shopify variant, decoded for read-only回显.
 * {@code bindingId}/{@code candidateId} are stable identifiers preserved for S1-b (per-variant
 * auto-bind) to reference/extend without re-deriving. Source context is decoded from the confirmed
 * candidate's structured audit reason.
 */
@Data
@Accessors(chain = true)
public class SkuVariantBindingVO {
    private Long bindingId;
    private Long candidateId;
    private String tangbuyProductId;
    private String tangbuySkuId;
    private BigDecimal matchScore;
    private String querySource;
    private String appliedQuery;
    private String detailUrl;
}
