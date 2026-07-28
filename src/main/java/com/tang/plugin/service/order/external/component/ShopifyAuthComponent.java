package com.tang.plugin.service.order.external.component;

import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.ShopifyProperties;
import com.tang.plugin.service.order.external.client.ShopifyGraphqlClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Shopify OAuth / token network calls only.
 *
 * <p>Public apps must request <em>expiring</em> offline tokens ({@code expiring=1}). Legacy
 * non-expiring tokens are migrated once via token exchange, then refreshed with
 * {@code grant_type=refresh_token}.
 */
@Slf4j
@Component
public class ShopifyAuthComponent {

    private static final String GRANT_TOKEN_EXCHANGE =
            "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String TOKEN_TYPE_OFFLINE =
            "urn:shopify:params:oauth:token-type:offline-access-token";
    private static final String TOKEN_TYPE_ID =
            "urn:ietf:params:oauth:token-type:id_token";

    @Resource
    private ShopifyProperties shopifyProperties;

    private final RestClient restClient = RestClient.create();

    public String buildInstallRedirectUrl(String shopDomain, String state) {
        String domain = ShopifyGraphqlClient.normalizeDomain(shopDomain);
        return "https://" + domain + "/admin/oauth/authorize"
                + "?client_id=" + shopifyProperties.getApiKey()
                + "&scope=" + shopifyProperties.getScopes()
                + "&redirect_uri=" + urlEncode(shopifyProperties.getRedirectUri())
                + "&state=" + urlEncode(state);
    }

    /** Authorization-code → expiring offline access token. */
    public JSONObject exchangeAccessToken(String shopDomain, String code) {
        JSONObject body = new JSONObject();
        body.put("client_id", StringUtils.trim(shopifyProperties.getApiKey()));
        body.put("client_secret", StringUtils.trim(shopifyProperties.getApiSecret()));
        body.put("code", code);
        body.put("expiring", "1");
        return postToken(shopDomain, body, "authorization-code");
    }

    /**
     * Exchange a Shopify App Bridge session token (id_token) for an expiring offline access token.
     * Preferred when the app is already installed in Admin — avoids a top-level OAuth redirect.
     * @see <a href="https://shopify.dev/docs/apps/build/authentication-authorization/access-tokens/token-exchange">Token exchange</a>
     */
    public JSONObject exchangeSessionTokenForOffline(String shopDomain, String sessionToken) {
        if (StringUtils.isBlank(sessionToken)) {
            throw new CustomException("Shopify session token blank, shopDomain=" + shopDomain);
        }
        JSONObject body = new JSONObject();
        body.put("client_id", StringUtils.trim(shopifyProperties.getApiKey()));
        body.put("client_secret", StringUtils.trim(shopifyProperties.getApiSecret()));
        body.put("grant_type", GRANT_TOKEN_EXCHANGE);
        body.put("subject_token", sessionToken.trim());
        body.put("subject_token_type", TOKEN_TYPE_ID);
        body.put("requested_token_type", TOKEN_TYPE_OFFLINE);
        body.put("expiring", "1");
        return postToken(shopDomain, body, "session-token-exchange");
    }

    /** Rotate an expiring offline access token using its refresh token. */
    public JSONObject refreshAccessToken(String shopDomain, String refreshToken) {
        if (StringUtils.isBlank(refreshToken)) {
            throw new CustomException("Shopify refresh_token blank, shopDomain=" + shopDomain);
        }
        JSONObject body = new JSONObject();
        body.put("client_id", StringUtils.trim(shopifyProperties.getApiKey()));
        body.put("client_secret", StringUtils.trim(shopifyProperties.getApiSecret()));
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);
        return postToken(shopDomain, body, "refresh");
    }

    /**
     * One-time migration: exchange a legacy non-expiring offline token for an expiring pair.
     * Irreversible — the old token is revoked on success.
     */
    public JSONObject migrateToExpiringOfflineToken(String shopDomain, String nonExpiringAccessToken) {
        if (StringUtils.isBlank(nonExpiringAccessToken)) {
            throw new CustomException("Shopify migrate subject_token blank, shopDomain=" + shopDomain);
        }
        JSONObject body = new JSONObject();
        body.put("client_id", StringUtils.trim(shopifyProperties.getApiKey()));
        body.put("client_secret", StringUtils.trim(shopifyProperties.getApiSecret()));
        body.put("grant_type", GRANT_TOKEN_EXCHANGE);
        body.put("subject_token", nonExpiringAccessToken);
        body.put("subject_token_type", TOKEN_TYPE_OFFLINE);
        body.put("requested_token_type", TOKEN_TYPE_OFFLINE);
        body.put("expiring", "1");
        return postToken(shopDomain, body, "migrate-expiring");
    }

    private JSONObject postToken(String shopDomain, JSONObject body, String kind) {
        String domain = ShopifyGraphqlClient.normalizeDomain(shopDomain);
        String url = "https://" + domain + "/admin/oauth/access_token";
        try {
            String raw = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toJSONString())
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (request, response) -> {
                                String errBody = new String(response.getBody().readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8);
                                log.error("Shopify token {} HTTP {} shopDomain={} body={}",
                                        kind, response.getStatusCode(), domain, errBody);
                                throw new CustomException("Shopify token " + kind + " HTTP "
                                        + response.getStatusCode() + ", shopDomain=" + domain
                                        + ", body=" + errBody);
                            })
                    .body(String.class);
            JSONObject json = JSONObject.parseObject(raw);
            if (json == null || StringUtils.isBlank(json.getString("access_token"))) {
                log.error("Shopify token {} empty shopDomain={} raw={}", kind, domain, raw);
                throw new CustomException("Shopify token " + kind + " failed, shopDomain=" + domain);
            }
            boolean expiring = StringUtils.isNotBlank(json.getString("refresh_token"));
            log.info("Shopify token {} ok shopDomain={} expiring={} expiresIn={}",
                    kind, domain, expiring, json.get("expires_in"));
            if (!expiring) {
                log.warn("Shopify token {} returned non-expiring token for shopDomain={}. "
                        + "Admin API may reject it; ensure expiring=1 is accepted by this app.",
                        kind, domain);
            }
            return json;
        } catch (CustomException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("Shopify token {} HTTP failed shopDomain={}", kind, domain, e);
            throw new CustomException("Shopify token " + kind + " HTTP failed, shopDomain=" + domain
                    + ", cause=" + e.getMessage(), e);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(StringUtils.defaultString(value), java.nio.charset.StandardCharsets.UTF_8);
    }
}
