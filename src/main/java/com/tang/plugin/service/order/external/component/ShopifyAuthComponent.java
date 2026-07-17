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
 */
@Slf4j
@Component
public class ShopifyAuthComponent {

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

    public JSONObject exchangeAccessToken(String shopDomain, String code) {
        String domain = ShopifyGraphqlClient.normalizeDomain(shopDomain);
        String url = "https://" + domain + "/admin/oauth/access_token";
        JSONObject body = new JSONObject();
        body.put("client_id", StringUtils.trim(shopifyProperties.getApiKey()));
        body.put("client_secret", StringUtils.trim(shopifyProperties.getApiSecret()));
        body.put("code", code);
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
                                log.error("Shopify token exchange HTTP {} shopDomain={} body={}",
                                        response.getStatusCode(), domain, errBody);
                                throw new CustomException("Shopify token exchange HTTP "
                                        + response.getStatusCode() + ", shopDomain=" + domain
                                        + ", body=" + errBody);
                            })
                    .body(String.class);
            JSONObject json = JSONObject.parseObject(raw);
            if (json == null || StringUtils.isBlank(json.getString("access_token"))) {
                log.error("Shopify token exchange empty shopDomain={} raw={}", domain, raw);
                throw new CustomException("Shopify token exchange failed, shopDomain=" + domain);
            }
            log.info("Shopify token exchange ok shopDomain={}", domain);
            return json;
        } catch (CustomException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("Shopify token exchange HTTP failed shopDomain={}", domain, e);
            throw new CustomException("Shopify token exchange HTTP failed, shopDomain=" + domain
                    + ", cause=" + e.getMessage(), e);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(StringUtils.defaultString(value), java.nio.charset.StandardCharsets.UTF_8);
    }
}
