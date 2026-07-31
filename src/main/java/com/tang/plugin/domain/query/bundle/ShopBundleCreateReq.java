package com.tang.plugin.domain.query.bundle;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ShopBundleCreateReq {
    private String shopName;
    /** Card product id — becomes default first component + title seed. */
    private String contextProductId;
    private String title;
    private BigDecimal parentPrice;
    private List<ComponentInput> components;
    /** Optional suggested discount % for Discount Function / margin UI. */
    private BigDecimal discountPercent;

    @Data
    public static class ComponentInput {
        private String productId;
        private Integer quantity;
        /** Optional Shopify variant id (numeric or GID). When set, optionSelections are locked to that variant. */
        private String variantId;
    }
}
