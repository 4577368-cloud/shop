package com.tang.plugin.service.webhook.handler.impl.shopify;

import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.bo.PluginShopBO;
import com.tang.plugin.domain.entity.order.ExternalOrder;
import com.tang.plugin.domain.entity.user.ShopifyStoreAuth;
import com.tang.plugin.enums.PluginType;
import com.tang.plugin.enums.webhook.ShopifyWebhookEventEnum;
import com.tang.plugin.service.order.external.adapter.ShopifyExternalOrderAdapter;
import com.tang.plugin.service.order.external.component.ShopifyOrderComponent;
import com.tang.plugin.service.order.external.strategy.impl.ShopifyOrderStrategyImpl;
import com.tang.plugin.service.user.ShopifyStoreAuthService;
import com.tang.plugin.service.webhook.handler.ShopifyWebhookEventHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Shared order webhook path: ACTIVE auth check → GraphQL backfill → Adapter → Strategy ingest.
 */
@Slf4j
abstract class AbstractShopifyOrderWebhookHandler implements ShopifyWebhookEventHandler {

    @Resource
    private ShopifyStoreAuthService shopifyStoreAuthService;
    @Resource
    private ShopifyOrderComponent shopifyOrderComponent;
    @Resource
    private ShopifyExternalOrderAdapter shopifyExternalOrderAdapter;
    @Resource
    private ShopifyOrderStrategyImpl shopifyOrderStrategyImpl;

    protected void ingestOrderWebhook(String shopDomain, String webhookId, String rawPayload, String eventLabel) {
        ShopifyStoreAuth auth = shopifyStoreAuthService.findActiveByShopDomain(shopDomain)
                .orElseThrow(() -> new CustomException(
                        "Shopify order webhook rejected, auth not ACTIVE, shopDomain=" + shopDomain));

        JSONObject payload = JSONObject.parseObject(rawPayload);
        String orderGid = resolveOrderGid(payload);
        if (StringUtils.isBlank(orderGid)) {
            throw new CustomException("Shopify order webhook missing order id, shopDomain=" + shopDomain
                    + ", webhookId=" + webhookId);
        }

        JSONObject orderNode = shopifyOrderComponent.fetchOrderById(
                auth.getShopName(), auth.getShopDomain(), auth.getAccessToken(), orderGid);
        ExternalOrder externalOrder =
                shopifyExternalOrderAdapter.convertToExternalOrder(orderNode, auth.getShopName());

        PluginShopBO shopBO = new PluginShopBO()
                .setShopName(auth.getShopName())
                .setShopType(PluginType.SHOPIFY);

        shopifyOrderStrategyImpl.ingestExternalOrder(externalOrder, shopBO);
        log.info("Shopify {} ingested shopDomain={} shopName={} orderId={} webhookId={}",
                eventLabel, shopDomain, auth.getShopName(), externalOrder.getOrderId(), webhookId);
    }

    /**
     * Prefer admin_graphql_api_id / graphql id; fallback to REST numeric id → Order GID.
     */
    static String resolveOrderGid(JSONObject payload) {
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
            return "gid://shopify/Order/" + idStr;
        }
        return null;
    }
}
