package com.tang.plugin.domain.entity.bundle;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

@Data
@Accessors(chain = true)
public class ShopBundleCampaign {
    private String id;
    private String shopName;
    /** fixed_kit | mix_match | byob | product_offer */
    private String playType;
    private String title;
    /** ACTIVE | DRAFT | ARCHIVED */
    private String status;
    private String ruleJson;
    private String poolJson;
    private String shopifyRefsJson;
    private Long linkedBundleId;
    private int delFlag;
    private Instant createdAt;
    private Instant updatedAt;
}
