package com.tang.plugin.domain.dto.bundle;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

@Data
@Accessors(chain = true)
public class ShopBundleCampaignVO {
    private String id;
    private String shopName;
    private String playType;
    private String title;
    private String status;
    private String ruleJson;
    private String poolJson;
    private String shopifyRefsJson;
    private Long linkedBundleId;
    private Integer poolCount;
    private Instant updatedAt;
}
