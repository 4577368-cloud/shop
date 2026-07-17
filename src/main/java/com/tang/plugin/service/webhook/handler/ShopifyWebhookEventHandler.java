package com.tang.plugin.service.webhook.handler;

import com.tang.plugin.enums.webhook.ShopifyWebhookEventEnum;

public interface ShopifyWebhookEventHandler {

    boolean supports(ShopifyWebhookEventEnum eventType);

    void handle(String shopDomain, String webhookId, String rawPayload);
}
