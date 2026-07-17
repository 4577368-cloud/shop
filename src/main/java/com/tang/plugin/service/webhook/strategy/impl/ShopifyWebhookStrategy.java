package com.tang.plugin.service.webhook.strategy.impl;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.ShopifyProperties;
import com.tang.plugin.enums.webhook.ShopifyWebhookEventEnum;
import com.tang.plugin.repository.ShopifyWebhookDeliveryRepository;
import com.tang.plugin.service.order.external.client.ShopifyGraphqlClient;
import com.tang.plugin.service.webhook.handler.ShopifyWebhookEventHandler;
import com.tang.plugin.utils.ShopifyHmacUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Component
public class ShopifyWebhookStrategy {

    @Resource
    private ShopifyProperties shopifyProperties;
    @Resource
    private ShopifyWebhookDeliveryRepository shopifyWebhookDeliveryRepository;
    @Resource
    private ObjectProvider<ShopifyWebhookEventHandler> handlerProvider;

    public void handle(String topic, String shopDomainHeader, String hmac, String webhookId, byte[] rawBody) {
        if (StringUtils.isAnyBlank(topic, shopDomainHeader, hmac) || rawBody == null) {
            throw new CustomException("Shopify webhook missing required headers/body");
        }
        if (!ShopifyHmacUtils.verifyWebhookRawBodyHmac(rawBody, hmac, shopifyProperties.getApiSecret())) {
            log.error("Shopify webhook HMAC invalid shopDomain={} topic={} webhookId={}",
                    shopDomainHeader, topic, webhookId);
            throw new CustomException("Shopify webhook HMAC invalid");
        }

        String shopDomain = ShopifyGraphqlClient.normalizeDomain(shopDomainHeader).toLowerCase();
        if (!shopifyWebhookDeliveryRepository.tryRecord(webhookId, shopDomain, topic)) {
            log.info("Shopify webhook duplicate skipped shopDomain={} topic={} webhookId={}",
                    shopDomain, topic, webhookId);
            return;
        }

        ShopifyWebhookEventEnum event = ShopifyWebhookEventEnum.fromTopic(topic);
        if (event == null) {
            log.warn("Shopify webhook unsupported topic shopDomain={} topic={} webhookId={}",
                    shopDomain, topic, webhookId);
            return;
        }
        if (event == ShopifyWebhookEventEnum.PRODUCTS_UPDATE) {
            log.info("Shopify products/update skeleton ignore shopDomain={} webhookId={}", shopDomain, webhookId);
            return;
        }

        String payload = new String(rawBody, StandardCharsets.UTF_8);
        List<ShopifyWebhookEventHandler> handlers = handlerProvider.orderedStream().toList();
        ShopifyWebhookEventHandler matched = handlers.stream()
                .filter(h -> h.supports(event))
                .findFirst()
                .orElse(null);
        if (matched == null) {
            log.warn("Shopify webhook no handler shopDomain={} topic={} webhookId={}",
                    shopDomain, topic, webhookId);
            return;
        }
        matched.handle(shopDomain, webhookId, payload);
    }
}
