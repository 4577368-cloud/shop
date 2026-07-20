package com.tang.plugin.service.webhook.handler.impl.shopify;

import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;

/**
 * Shared helpers for Shopify product webhooks (create / update / delete).
 */
final class ShopifyProductWebhookSupport {

    private ShopifyProductWebhookSupport() {
    }

    /** Prefer admin_graphql_api_id; fallback REST numeric id → Product GID. */
    static String resolveProductGid(JSONObject payload) {
        if (payload == null) {
            return null;
        }
        String gqlId = payload.getString("admin_graphql_api_id");
        if (StringUtils.isNotBlank(gqlId)) {
            return gqlId;
        }
        Object id = payload.get("id");
        if (id == null) {
            return null;
        }
        String idStr = String.valueOf(id);
        if (idStr.startsWith("gid://")) {
            return idStr;
        }
        if (StringUtils.isNumeric(idStr)) {
            return "gid://shopify/Product/" + idStr;
        }
        return null;
    }
}
