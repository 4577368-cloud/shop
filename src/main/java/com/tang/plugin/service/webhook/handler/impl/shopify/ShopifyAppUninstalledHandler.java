package com.tang.plugin.service.webhook.handler.impl.shopify;

import com.tang.plugin.enums.webhook.ShopifyWebhookEventEnum;
import com.tang.plugin.service.user.ShopifyStoreAuthService;
import com.tang.plugin.service.webhook.handler.ShopifyWebhookEventHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * app/uninstalled — no GraphQL backfill; revoke auth by shopDomain.
 */
@Slf4j
@Component
public class ShopifyAppUninstalledHandler implements ShopifyWebhookEventHandler {

    @Resource
    private ShopifyStoreAuthService shopifyStoreAuthService;

    @Override
    public boolean supports(ShopifyWebhookEventEnum eventType) {
        return eventType == ShopifyWebhookEventEnum.APP_UNINSTALLED;
    }

    @Override
    public void handle(String shopDomain, String webhookId, String rawPayload) {
        shopifyStoreAuthService.markUninstalledByShopDomain(shopDomain);
        log.info("Shopify app/uninstalled processed shopDomain={} webhookId={}", shopDomain, webhookId);
    }
}
