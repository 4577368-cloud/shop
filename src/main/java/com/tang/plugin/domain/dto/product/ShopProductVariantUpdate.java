package com.tang.plugin.domain.dto.product;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Phase 4: one variant row to write back to Shopify (price and/or inventory).
 * Null price / inventoryQuantity means "leave unchanged".
 */
@Data
public class ShopProductVariantUpdate {
    /** Required. Shopify ProductVariant GID ({@code third_platform_sku_id}). */
    private String thirdPlatformSkuId;
    private BigDecimal price;
    private Integer inventoryQuantity;
}
