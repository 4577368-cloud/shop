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

    @Data
    public static class ComponentInput {
        private String productId;
        private Integer quantity;
    }
}
