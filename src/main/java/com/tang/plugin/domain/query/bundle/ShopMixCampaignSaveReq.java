package com.tang.plugin.domain.query.bundle;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ShopMixCampaignSaveReq {
    private String shopName;
    private String id;
    private String title;
    /** ACTIVE | DRAFT | ARCHIVED */
    private String status;
    private Map<String, Object> rule;
    private List<String> poolProductIds;
}
