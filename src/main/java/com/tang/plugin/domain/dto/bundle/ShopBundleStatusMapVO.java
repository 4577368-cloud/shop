package com.tang.plugin.domain.dto.bundle;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

@Data
@Accessors(chain = true)
public class ShopBundleStatusMapVO {
    private BundlesFeatureVO feature;
    /** productId (numeric) → compact status for list cards. */
    private Map<String, CardStatus> byProductId;

    @Data
    @Accessors(chain = true)
    public static class CardStatus {
        private Long bundleId;
        private String status;
        private String parentProductId;
        private String parentTitle;
        private int componentCount;
        /** Component Shopify product ids (numeric) — for kit-parent cards. */
        private List<String> componentProductIds;
        private boolean asParent;
        private boolean asComponent;
        private boolean managedByApp;
    }
}
