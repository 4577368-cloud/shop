package com.tang.plugin.domain.query.bundle;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ShopBundleUpdateReq {
    private String shopName;
    private Long bundleId;
    private String title;
    private BigDecimal parentPrice;
    /**
     * Optional Shopify variant id for the context (base) component.
     * When set, optionSelections for the base product are pinned to that variant.
     */
    private String contextVariantId;
    /** Optional percent off vs sum of component list prices (stored + metafield for Function). */
    private BigDecimal discountPercent;
    private List<ShopBundleCreateReq.ComponentInput> components;
}
