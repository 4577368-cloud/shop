package com.tang.plugin.domain.query.bundle;

import lombok.Data;

import java.util.Map;

@Data
public class ShopByobCampaignSaveReq {
    private String shopName;
    private String id;
    private String title;
    /** DRAFT | ACTIVE | ARCHIVED */
    private String status;
    private Map<String, Object> rule;
}
