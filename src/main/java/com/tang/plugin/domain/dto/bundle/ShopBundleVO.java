package com.tang.plugin.domain.dto.bundle;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Accessors(chain = true)
public class ShopBundleVO {
    private Long id;
    private String shopName;
    private String contextProductId;
    private String parentProductId;
    private String parentVariantId;
    private String parentTitle;
    private BigDecimal parentPrice;
    private BigDecimal discountPercent;
    private String status;
    private boolean managedByApp;
    private String errorMessage;
    private Instant syncedAt;
    private List<ComponentVO> components;

    @Data
    @Accessors(chain = true)
    public static class ComponentVO {
        private String productId;
        private int quantity;
        private String title;
        private String variantId;
    }
}
