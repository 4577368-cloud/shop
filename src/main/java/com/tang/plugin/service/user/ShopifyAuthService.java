package com.tang.plugin.service.user;

import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.ShopifyProperties;
import com.tang.plugin.service.order.external.client.ShopifyGraphqlClient;
import com.tang.plugin.service.order.external.component.ShopifyAuthComponent;
import com.tang.plugin.service.webhook.component.ShopifyWebhookComponent;
import com.tang.plugin.utils.ShopifyHmacUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shopify OAuth install / callback orchestration. Fulfillment mount explicitly skipped.
 */
@Slf4j
@Service
public class ShopifyAuthService {

    @Resource
    private ShopifyProperties shopifyProperties;
    @Resource
    private ShopifyAuthComponent shopifyAuthComponent;
    @Resource
    private ShopifyStoreAuthService shopifyStoreAuthService;
    @Resource
    private ShopifyWebhookComponent shopifyWebhookComponent;

    public String buildInstallUrl(String shop) {
        assertConfigured();
        String shopDomain = normalizeAndValidateShop(shop);
        String state = UUID.randomUUID().toString().replace("-", "");
        String redirect = shopifyAuthComponent.buildInstallRedirectUrl(shopDomain, state);
        log.info("Shopify install redirect prepared shopDomain={}", shopDomain);
        return redirect;
    }

    public Map<String, Object> handleCallback(Map<String, String> queryParams) {
        assertConfigured();
        if (queryParams == null || queryParams.isEmpty()) {
            throw new CustomException("Shopify callback params empty");
        }
        String shopHint = queryParams.get("shop");
        String hmac = queryParams.get("hmac");
        String apiSecret = StringUtils.trimToEmpty(shopifyProperties.getApiSecret());
        if (!ShopifyHmacUtils.verifyOAuthQueryHmac(queryParams, hmac, apiSecret)) {
            log.error("Shopify callback HMAC invalid shop={} paramKeys={}",
                    shopHint, queryParams.keySet());
            throw new CustomException(
                    "Shopify callback HMAC invalid. Check TANG_PLUGIN_SHOPIFY_API_SECRET matches Partner App client secret.");
        }

        String shopDomain = normalizeAndValidateShop(queryParams.get("shop"));
        String code = queryParams.get("code");
        if (StringUtils.isBlank(code)) {
            throw new CustomException("Shopify callback code blank, shopDomain=" + shopDomain);
        }

        JSONObject tokenJson;
        try {
            tokenJson = shopifyAuthComponent.exchangeAccessToken(shopDomain, code);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("Shopify token exchange unexpected shopDomain={}", shopDomain, e);
            throw new CustomException("Shopify token exchange failed, shopDomain=" + shopDomain
                    + ", cause=" + e.getMessage(), e);
        }
        String accessToken = tokenJson.getString("access_token");
        String scope = tokenJson.getString("scope");
        String shopName = toShopName(shopDomain);

        Long authId;
        try {
            authId = shopifyStoreAuthService.saveActiveAuth(shopName, shopDomain, accessToken, scope);
        } catch (Exception e) {
            log.error("Shopify auth persist failed shopDomain={}", shopDomain, e);
            throw new CustomException("Shopify auth save failed, shopDomain=" + shopDomain
                    + ", cause=" + e.getMessage(), e);
        }
        log.info("Shopify auth saved shopDomain={} shopName={} authId={}", shopDomain, shopName, authId);

        // Fulfillment mount intentionally skipped in phase-2.
        try {
            shopifyWebhookComponent.registerDefaultWebhooks(shopName, shopDomain, accessToken);
        } catch (Exception e) {
            log.error("Shopify webhook register after auth failed shopDomain={}", shopDomain, e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("shopDomain", shopDomain);
        result.put("shopName", shopName);
        result.put("authId", authId);
        result.put("fulfillmentMounted", false);
        result.put("note", "Auth saved. Webhooks registered (orders/create, orders/updated, app/uninstalled).");
        return result;
    }

    private void assertConfigured() {
        String apiKey = StringUtils.trimToEmpty(shopifyProperties.getApiKey());
        String apiSecret = StringUtils.trimToEmpty(shopifyProperties.getApiSecret());
        if (StringUtils.isAnyBlank(apiKey, apiSecret)) {
            throw new CustomException(
                    "Shopify api-key/api-secret not configured on server (check Render env TANG_PLUGIN_SHOPIFY_API_KEY / _API_SECRET)");
        }
        if (StringUtils.isBlank(shopifyProperties.getRedirectUri())) {
            throw new CustomException("Shopify redirect-uri not configured (TANG_PLUGIN_SHOPIFY_REDIRECT_URI)");
        }
    }

    private static String normalizeAndValidateShop(String shop) {
        String domain = ShopifyGraphqlClient.normalizeDomain(shop);
        if (StringUtils.isBlank(domain) || !domain.endsWith(".myshopify.com")) {
            throw new CustomException("Invalid Shopify shop domain: " + shop);
        }
        return domain.toLowerCase();
    }

    private static String toShopName(String shopDomain) {
        return StringUtils.removeEnd(shopDomain, ".myshopify.com");
    }
}
