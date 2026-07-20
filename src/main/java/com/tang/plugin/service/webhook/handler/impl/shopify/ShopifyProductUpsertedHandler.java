package com.tang.plugin.service.webhook.handler.impl.shopify;

import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.bo.product.ShopifyProductMirror;
import com.tang.plugin.domain.entity.user.ShopifyStoreAuth;
import com.tang.plugin.enums.webhook.ShopifyWebhookEventEnum;
import com.tang.plugin.service.product.ProductSyncService;
import com.tang.plugin.service.publish.component.ExchangeRateComponent;
import com.tang.plugin.service.publish.component.shopify.ShopifyProductComponent;
import com.tang.plugin.service.publish.convert.ShopifyProductRequestAdapter;
import com.tang.plugin.service.user.ShopifyStoreAuthService;
import com.tang.plugin.service.webhook.handler.ShopifyWebhookEventHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * products/create + products/update — GraphQL backfill then upsert the local SPU/SKU/media mirror.
 */
@Slf4j
@Component
public class ShopifyProductUpsertedHandler implements ShopifyWebhookEventHandler {

    private static final String DEFAULT_CURRENCY = "USD";

    @Resource
    private ShopifyStoreAuthService shopifyStoreAuthService;
    @Resource
    private ShopifyProductComponent shopifyProductComponent;
    @Resource
    private ShopifyProductRequestAdapter shopifyProductRequestAdapter;
    @Resource
    private ExchangeRateComponent exchangeRateComponent;
    @Resource
    private ProductSyncService productSyncService;

    @Override
    public boolean supports(ShopifyWebhookEventEnum eventType) {
        return eventType == ShopifyWebhookEventEnum.PRODUCTS_CREATE
                || eventType == ShopifyWebhookEventEnum.PRODUCTS_UPDATE;
    }

    @Override
    public void handle(String shopDomain, String webhookId, String rawPayload) {
        ShopifyStoreAuth auth = shopifyStoreAuthService.findActiveByShopDomain(shopDomain)
                .orElseThrow(() -> new CustomException(
                        "Shopify product webhook rejected, auth not ACTIVE, shopDomain=" + shopDomain));

        JSONObject payload = JSONObject.parseObject(rawPayload);
        String productGid = ShopifyProductWebhookSupport.resolveProductGid(payload);
        if (StringUtils.isBlank(productGid)) {
            throw new CustomException("Shopify product webhook missing product id, shopDomain=" + shopDomain
                    + ", webhookId=" + webhookId);
        }

        JSONObject node = shopifyProductComponent.fetchProductById(
                auth.getShopName(), auth.getShopDomain(), auth.getAccessToken(), productGid);
        if (node == null) {
            log.info("Shopify product webhook skip (product gone) shopDomain={} productId={} webhookId={}",
                    shopDomain, productGid, webhookId);
            return;
        }

        BigDecimal rate = exchangeRateComponent.getExchangeRate(DEFAULT_CURRENCY);
        ShopifyProductMirror mirror = shopifyProductRequestAdapter.convert(
                node, auth.getShopName(), DEFAULT_CURRENCY, rate);
        if (mirror == null || mirror.getProduct() == null) {
            throw new CustomException("Shopify product webhook convert failed, shopDomain=" + shopDomain
                    + ", productId=" + productGid);
        }

        productSyncService.upsertOne(mirror.getProduct(), mirror.getSkuList());
        log.info("Shopify product webhook upserted shopDomain={} shopName={} productId={} skus={} webhookId={}",
                shopDomain, auth.getShopName(), productGid,
                mirror.getSkuList() == null ? 0 : mirror.getSkuList().size(),
                webhookId);
    }
}
