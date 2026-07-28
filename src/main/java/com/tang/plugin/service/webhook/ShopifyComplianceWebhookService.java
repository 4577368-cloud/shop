package com.tang.plugin.service.webhook;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.ShopifyProperties;
import com.tang.plugin.service.order.external.client.ShopifyGraphqlClient;
import com.tang.plugin.service.user.ShopifyShopRedactService;
import com.tang.plugin.utils.ShopifyHmacUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Mandatory Shopify GDPR / compliance webhooks.
 *
 * <p>This app requests only {@code read_products}/{@code write_products} — it does not
 * store Shopify customer PII. {@code customers/data_request} and {@code customers/redact}
 * are acknowledged as no-ops after HMAC verification. {@code shop/redact} purges
 * shop-scoped mirror and binding data.
 */
@Slf4j
@Service
public class ShopifyComplianceWebhookService {

    @Resource
    private ShopifyProperties shopifyProperties;
    @Resource
    private ShopifyShopRedactService shopifyShopRedactService;

    public void handle(String topic, String shopDomainHeader, String hmac, String webhookId, byte[] rawBody) {
        if (StringUtils.isAnyBlank(topic, hmac) || rawBody == null) {
            throw new CustomException("Shopify compliance webhook missing required headers/body");
        }
        if (!ShopifyHmacUtils.verifyWebhookRawBodyHmac(rawBody, hmac, shopifyProperties.getApiSecret())) {
            log.error("Shopify compliance HMAC invalid topic={} webhookId={} shop={}",
                    topic, webhookId, shopDomainHeader);
            throw new CustomException("Shopify webhook HMAC invalid", 401);
        }

        String payload = new String(rawBody, java.nio.charset.StandardCharsets.UTF_8);
        JSONObject json;
        try {
            json = JSON.parseObject(payload);
        } catch (Exception e) {
            throw new CustomException("Shopify compliance webhook body is not JSON");
        }

        String shopDomain = resolveShopDomain(shopDomainHeader, json);
        String normalizedTopic = topic.trim().toLowerCase();

        switch (normalizedTopic) {
            case "customers/data_request" -> handleCustomerDataRequest(shopDomain, webhookId, json);
            case "customers/redact" -> handleCustomerRedact(shopDomain, webhookId, json);
            case "shop/redact" -> handleShopRedact(shopDomain, webhookId, json);
            default -> log.warn(
                    "Shopify compliance unsupported topic={} shop={} webhookId={}",
                    topic, shopDomain, webhookId);
        }
    }

    private void handleCustomerDataRequest(String shopDomain, String webhookId, JSONObject json) {
        // No customer / order PII is stored for this products-only app.
        log.info(
                "GDPR customers/data_request acknowledged (no customer PII stored) shop={} webhookId={} customer={}",
                shopDomain,
                webhookId,
                json == null ? null : json.getJSONObject("customer"));
    }

    private void handleCustomerRedact(String shopDomain, String webhookId, JSONObject json) {
        log.info(
                "GDPR customers/redact acknowledged (no customer PII stored) shop={} webhookId={} customer={}",
                shopDomain,
                webhookId,
                json == null ? null : json.getJSONObject("customer"));
    }

    private void handleShopRedact(String shopDomain, String webhookId, JSONObject json) {
        if (StringUtils.isBlank(shopDomain)) {
            throw new CustomException("shop/redact missing shop domain");
        }
        int touched = shopifyShopRedactService.redactShop(shopDomain);
        log.info(
                "GDPR shop/redact completed shop={} webhookId={} rowsTouchedApprox={} payloadShopId={}",
                shopDomain,
                webhookId,
                touched,
                json == null ? null : json.get("shop_id"));
    }

    private static String resolveShopDomain(String header, JSONObject json) {
        if (StringUtils.isNotBlank(header)) {
            return ShopifyGraphqlClient.normalizeDomain(header).toLowerCase();
        }
        if (json != null && StringUtils.isNotBlank(json.getString("shop_domain"))) {
            return ShopifyGraphqlClient.normalizeDomain(json.getString("shop_domain")).toLowerCase();
        }
        return "";
    }
}
