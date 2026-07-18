package com.tang.plugin.service.webhook.component;

import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.ShopifyProperties;
import com.tang.plugin.enums.webhook.ShopifyWebhookEventEnum;
import com.tang.plugin.service.order.external.client.ShopifyGraphqlClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * Registers Shopify webhook subscriptions via Admin GraphQL.
 */
@Slf4j
@Component
public class ShopifyWebhookComponent {

    private static final String CREATE_SUBSCRIPTION = """
            mutation webhookSubscriptionCreate($topic: WebhookSubscriptionTopic!, $callbackUrl: URL!) {
              webhookSubscriptionCreate(
                topic: $topic
                webhookSubscription: {callbackUrl: $callbackUrl, format: JSON}
              ) {
                userErrors { field message }
                webhookSubscription { id topic }
              }
            }
            """;

    @Resource
    private ShopifyGraphqlClient shopifyGraphqlClient;
    @Resource
    private ShopifyProperties shopifyProperties;
    @Lazy
    @Resource
    private ShopifyWebhookComponent self;

    public void registerDefaultWebhooks(String shopName, String shopDomain, String accessToken) {
        String callbackUrl = StringUtils.removeEnd(shopifyProperties.getWebhookBaseUrl(), "/")
                + "/api/plugin/shopify/webhook";
        for (ShopifyWebhookEventEnum event : ShopifyWebhookEventEnum.registerOnAuth()) {
            try {
                self.createWebhookRetry(shopName, shopDomain, accessToken, event.getGraphqlTopic(), callbackUrl);
            } catch (Exception e) {
                log.error("Shopify register webhook failed shopDomain={} topic={}",
                        shopDomain, event.getTopic(), e);
            }
        }
    }

    @Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 2000))
    public void createWebhookRetry(String shopName, String shopDomain, String accessToken,
                                   String graphqlTopic, String callbackUrl) {
        createWebhook(shopName, shopDomain, accessToken, graphqlTopic, callbackUrl);
    }

    public void createWebhook(String shopName, String shopDomain, String accessToken,
                              String graphqlTopic, String callbackUrl) {
        if (StringUtils.isAnyBlank(shopDomain, accessToken, graphqlTopic, callbackUrl)) {
            throw new CustomException("createWebhook missing args, shopDomain=" + shopDomain);
        }
        JSONObject variables = new JSONObject();
        variables.put("topic", graphqlTopic);
        variables.put("callbackUrl", callbackUrl);
        JSONObject response = shopifyGraphqlClient.execute(
                shopName, shopDomain, accessToken, CREATE_SUBSCRIPTION, variables);
        JSONObject data = response.getJSONObject("data");
        JSONObject payload = data == null ? null : data.getJSONObject("webhookSubscriptionCreate");
        if (payload != null && payload.getJSONArray("userErrors") != null
                && !payload.getJSONArray("userErrors").isEmpty()) {
            String errors = payload.getJSONArray("userErrors").toString();
            // Idempotent: an existing subscription for this topic/callback is a success, not a failure.
            if (StringUtils.containsIgnoreCase(errors, "already been taken")) {
                log.info("Shopify webhook already registered (idempotent) shopDomain={} topic={}",
                        shopDomain, graphqlTopic);
                return;
            }
            log.error("Shopify webhook userErrors shopDomain={} topic={} errors={}",
                    shopDomain, graphqlTopic, errors);
            throw new CustomException("Shopify webhook create userErrors, shopDomain=" + shopDomain
                    + ", topic=" + graphqlTopic);
        }
        log.info("Shopify webhook created shopDomain={} topic={}", shopDomain, graphqlTopic);
    }
}
