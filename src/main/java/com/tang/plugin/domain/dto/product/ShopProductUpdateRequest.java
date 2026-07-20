package com.tang.plugin.domain.dto.product;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Phase 2–4 write-back body: editable Shopify product fields from the workbench drawer.
 */
@Data
public class ShopProductUpdateRequest {
    /** Required. Shopify product GID (third_platform_item_id). */
    private String itemId;
    private String title;
    private String description;
    /** ACTIVE / DRAFT / ARCHIVED */
    private String status;
    /**
     * Optional default-variant price (updates the lowest-position active variant).
     * Kept for back-compat; prefer {@link #variants} in Phase 4.
     * Null = leave unchanged (unless covered by {@link #variants}).
     */
    private BigDecimal defaultVariantPrice;
    /**
     * Phase 4: per-variant price / inventory updates. Null or empty = no multi-variant writes.
     */
    private List<ShopProductVariantUpdate> variants;
    /**
     * Phase 3 optimistic concurrency: mirror {@code updatedAt} from the last GET.
     * When set (and {@link #force} is false), a mismatch returns HTTP 409 PRODUCT_CONFLICT.
     * Null = skip the check (last-write-wins, back-compat).
     */
    private Instant expectedUpdatedAt;
    /**
     * When true, skip the {@link #expectedUpdatedAt} check and overwrite Shopify.
     */
    private Boolean force;
}
