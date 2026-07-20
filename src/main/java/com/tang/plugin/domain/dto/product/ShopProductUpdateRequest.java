package com.tang.plugin.domain.dto.product;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Phase 2 write-back body: editable Shopify product fields from the workbench drawer.
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
     * Null = leave variant price unchanged.
     */
    private BigDecimal defaultVariantPrice;
}
