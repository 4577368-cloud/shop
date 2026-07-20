package com.tang.plugin.service.webhook.handler.impl.shopify;

import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.entity.user.ShopifyStoreAuth;
import com.tang.plugin.enums.webhook.ShopifyWebhookEventEnum;
import com.tang.plugin.service.product.ProductMirrorDeleteService;
import com.tang.plugin.service.user.ShopifyStoreAuthService;
import com.tang.plugin.service.webhook.handler.ShopifyWebhookEventHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * products/delete — soft-delete the local SPU/SKU/media mirror (no GraphQL backfill; product is gone).
 */
@Slf4j
@Component
public class ShopifyProductDeletedHandler implements ShopifyWebhookEventHandler {

    @Resource
    private ShopifyStoreAuthService shopifyStoreAuthService;
    @Resource
    private ProductMirrorDeleteService productMirrorDeleteService;

    @Override
    public boolean supports(ShopifyWebhookEventEnum eventType) {
        return eventType == ShopifyWebhookEventEnum.PRODUCTS_DELETE;
    }

    @Override
    public void handle(String shopDomain, String webhookId, String rawPayload) {
        ShopifyStoreAuth auth = shopifyStoreAuthService.findActiveByShopDomain(shopDomain)
                .orElseThrow(() -> new CustomException(
                        "Shopify products/delete rejected, auth not ACTIVE, shopDomain=" + shopDomain));

        JSONObject payload = JSONObject.parseObject(rawPayload);
        String productGid = resolveProductGid(payload);
        if (StringUtils.isBlank(productGid)) {
            throw new CustomException("Shopify products/delete missing product id, shopDomain=" + shopDomain
                    + ", webhookId=" + webhookId);
        }

        boolean deleted = productMirrorDeleteService.softDeleteCascade(auth.getShopName(), productGid);
        log.info("Shopify products/delete processed shopDomain={} shopName={} productId={} deleted={} webhookId={}",
                shopDomain, auth.getShopName(), productGid, deleted, webhookId);
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
