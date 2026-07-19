package com.tang.plugin.domain.dto.match;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * A3-2b request: confirm a chosen 1688 image-search offer as the SKU-level binding for a shop product
 * (route B — default variant + default SKU). The backend resolves the Shopify variant GID from the
 * local SKU mirror; the frontend never sends it. {@code offerProductId} is the 1688 offer id and is
 * required. The resolution context ({@code imageSource}/{@code querySource}/{@code appliedQuery}) and
 * {@code detailUrl}/{@code similarityScore} come from the A3-2a preview and are kept for audit.
 */
@Data
@Accessors(chain = true)
public class ConfirmImageMatchDTO {
    private String shopName;
    private String thirdPlatformItemId;
    /** 1688 offer id (required). Becomes tangbuyProductId, and tangbuySkuId when offerSkuId is blank. */
    private String offerProductId;
    /** 1688 sku id when known; falls back to offerProductId (route B single-SKU assumption). */
    private String offerSkuId;
    /** Direct 1688 offer link, persisted into the audit reason. */
    private String detailUrl;
    /** 1688 image-search similarity (0..1); stored as matchScore (0 when absent — matchScore is required). */
    private Double similarityScore;
    /** ORIGINAL / SHOPIFY (from the A3-2a preview). */
    private String imageSource;
    /** NONE / TITLE / LLM (from the A3-2a preview). */
    private String querySource;
    /** Display value of the correction query (from the A3-2a preview). */
    private String appliedQuery;
}
