package com.tang.plugin.controller.webhook;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.service.webhook.ShopifyComplianceWebhookService;
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

/**
 * Shopify Admin webhooks + mandatory GDPR compliance endpoints.
 *
 * <p>Path aliases:
 * <ul>
 *   <li>{@code /webhook} — historical GraphQL registration callback</li>
 *   <li>{@code /webhooks} — {@code shopify.app.toml} subscription URI</li>
 *   <li>{@code /webhooks/compliance} — GDPR compliance_topics</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/shopify")
public class ShopifyWebhookController {

    @Resource
    private ShopifyWebhookStrategy shopifyWebhookStrategy;
    @Resource
    private ShopifyComplianceWebhookService shopifyComplianceWebhookService;

    @PostMapping({"/webhook", "/webhooks"})
    public ResponseEntity<Map<String, Object>> webhook(HttpServletRequest request) {
        return dispatchOperational(request);
    }

    @PostMapping({"/webhooks/compliance", "/webhook/compliance"})
    public ResponseEntity<Map<String, Object>> compliance(HttpServletRequest request) {
        String topic = request.getHeader("X-Shopify-Topic");
        String shopDomain = request.getHeader("X-Shopify-Shop-Domain");
        String hmac = request.getHeader("X-Shopify-Hmac-Sha256");
        String webhookId = request.getHeader("X-Shopify-Webhook-Id");

        byte[] rawBody = readBody(request, shopDomain, topic);
        log.info("Shopify compliance webhook received shopDomain={} topic={} webhookId={} bytes={}",
                shopDomain, topic, webhookId, rawBody.length);

        try {
            shopifyComplianceWebhookService.handle(topic, shopDomain, hmac, webhookId, rawBody);
        } catch (CustomException e) {
            log.error("Shopify compliance webhook rejected shopDomain={} topic={}: {}",
                    shopDomain, topic, e.getMessage());
            throw e;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> dispatchOperational(HttpServletRequest request) {
        String topic = request.getHeader("X-Shopify-Topic");
        String shopDomain = request.getHeader("X-Shopify-Shop-Domain");
        String hmac = request.getHeader("X-Shopify-Hmac-Sha256");
        String webhookId = request.getHeader("X-Shopify-Webhook-Id");

        byte[] rawBody = readBody(request, shopDomain, topic);
        log.info("Shopify webhook received shopDomain={} topic={} webhookId={} bytes={}",
                shopDomain, topic, webhookId, rawBody.length);

        shopifyWebhookStrategy.handle(topic, shopDomain, hmac, webhookId, rawBody);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        return ResponseEntity.ok(body);
    }

    private static byte[] readBody(HttpServletRequest request, String shopDomain, String topic) {
        try {
            return request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("Shopify webhook read body failed shopDomain={} topic={}", shopDomain, topic, e);
            return new byte[0];
        }
    }
}
