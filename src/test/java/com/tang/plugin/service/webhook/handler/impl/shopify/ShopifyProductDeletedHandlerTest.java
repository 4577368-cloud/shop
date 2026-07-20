package com.tang.plugin.service.webhook.handler.impl.shopify;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ShopifyProductDeletedHandlerTest {

    @Test
    void resolveProductGid_prefersAdminGraphqlId() {
        JSONObject payload = new JSONObject();
        payload.put("id", 123);
        payload.put("admin_graphql_api_id", "gid://shopify/Product/999");
        assertEquals("gid://shopify/Product/999", ShopifyProductDeletedHandler.resolveProductGid(payload));
    }

    @Test
    void resolveProductGid_fallsBackToNumericId() {
        JSONObject payload = new JSONObject();
        payload.put("id", 456789);
        assertEquals("gid://shopify/Product/456789", ShopifyProductDeletedHandler.resolveProductGid(payload));
    }

    @Test
    void resolveProductGid_nullPayload() {
        assertNull(ShopifyProductDeletedHandler.resolveProductGid(null));
    }
}
