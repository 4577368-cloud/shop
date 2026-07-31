package com.tang.plugin.domain.entity.bundle;

import com.tang.plugin.enums.bundle.ShopBundleStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Accessors(chain = true)
public class ShopProductBundle {
    private Long id;
    private String shopName;
    /** Shopify product id (numeric) that opened the composer — usually first component. */
    private String contextProductId;
    private String parentProductId;
    private String parentVariantId;
    private String parentTitle;
    private BigDecimal parentPrice;
    /** Optional percent discount for Function / UI (null = none). */
    private BigDecimal discountPercent;
    private String componentsJson;
    private ShopBundleStatus status;
    private String shopifyOperationId;
    private int managedByApp;
    private String errorMessage;
    private Instant syncedAt;
    private int delFlag;
    private Instant createdAt;
    private Instant updatedAt;
}
