package com.tang.plugin.service.webhook.handler.impl.shopify;

import com.tang.plugin.enums.webhook.ShopifyWebhookEventEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ShopifyOrderCreatedHandler extends AbstractShopifyOrderWebhookHandler {

    @Override
    public boolean supports(ShopifyWebhookEventEnum eventType) {
        return eventType == ShopifyWebhookEventEnum.ORDERS_CREATE;
    }

    @Override
    public void handle(String shopDomain, String webhookId, String rawPayload) {
        ingestOrderWebhook(shopDomain, webhookId, rawPayload, "orders/create");
    }
}
