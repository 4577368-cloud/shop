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
    /** PENDING = AI-suggested, awaiting confirmation; ACTIVE = confirmed. Null only for legacy rows. */
    private String bindStatus;
    /** Matched 1688 spec label for RULE/AI (auto-aligned) bindings, e.g. "Red / M"; null for IMAGE. */
    private String tangbuySkuSpec;
    /** How the binding was established: IMAGE (A3-2b) or RULE/AI (S1-b1 auto-align). */
    private String matchSource;
    private BigDecimal matchScore;
    private String querySource;
    private String appliedQuery;
    private String detailUrl;
}
