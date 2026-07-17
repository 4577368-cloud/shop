package com.tang.plugin.controller.webhook;

import com.tang.plugin.service.webhook.strategy.impl.ShopifyWebhookStrategy;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/plugin/shopify")
public class ShopifyWebhookController {

    @Resource
    private ShopifyWebhookStrategy shopifyWebhookStrategy;

    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> webhook(HttpServletRequest request) {
        String topic = request.getHeader("X-Shopify-Topic");
        String shopDomain = request.getHeader("X-Shopify-Shop-Domain");
        String hmac = request.getHeader("X-Shopify-Hmac-Sha256");
        String webhookId = request.getHeader("X-Shopify-Webhook-Id");

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("Shopify webhook read body failed shopDomain={} topic={}", shopDomain, topic, e);
            rawBody = new byte[0];
        }
        log.info("Shopify webhook received shopDomain={} topic={} webhookId={} bytes={}",
                shopDomain, topic, webhookId, rawBody.length);

        shopifyWebhookStrategy.handle(topic, shopDomain, hmac, webhookId, rawBody);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        return ResponseEntity.ok(body);
    }
}
