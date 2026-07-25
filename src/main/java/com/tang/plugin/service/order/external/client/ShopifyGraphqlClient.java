package com.tang.plugin.service.order.external.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.ShopifyProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

/**
 * Minimal Shopify Admin GraphQL transport. Only Order/Product Components may use this.
 */
@Slf4j
@Component
public class ShopifyGraphqlClient {

    @Resource
    private ShopifyProperties shopifyProperties;

    private final RestClient restClient = RestClient.create();

    public JSONObject execute(String shopName, String shopDomain, String accessToken,
                              String query, JSONObject variables) {
        if (StringUtils.isAnyBlank(shopDomain, accessToken, query)) {
            throw new CustomException("Shopify GraphQL request missing domain/token/query, shopName=" + shopName);
        }
        String normalizedDomain = normalizeDomain(shopDomain);
        String url = "https://" + normalizedDomain + "/admin/api/"
                + shopifyProperties.getApiVersion() + "/graphql.json";

        JSONObject body = new JSONObject();
        body.put("query", query);
        if (variables != null) {
            body.put("variables", variables);
        }

        String raw = restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Shopify-Access-Token", accessToken)
                .body(body.toJSONString())
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    String errBody = readBody(response.getBody());
                    HttpStatusCode status = response.getStatusCode();
                    log.error("Shopify GraphQL HTTP {} shopName={} domain={} body={}",
                            status, shopName, normalizedDomain, truncate(errBody, 800));
                    throw new CustomException(buildHttpErrorMessage(status, shopName, errBody));
                })
                .body(String.class);

        JSONObject response = JSON.parseObject(raw);
        if (response == null) {
            throw new CustomException("Shopify GraphQL empty response, shopName=" + shopName);
        }
        if (response.containsKey("errors")) {
            String detail = truncate(String.valueOf(response.get("errors")), 500);
            log.error("Shopify GraphQL errors shopName={} errors={}", shopName, detail);
            throw new CustomException(
                    "Shopify GraphQL errors, shopName=" + shopName + ", errors=" + detail);
        }
        return response;
    }

    private static String buildHttpErrorMessage(HttpStatusCode status, String shopName, String body) {
        int code = status.value();
        String hint = switch (code) {
            case 401 -> "re-authorize the store (access token invalid or revoked)";
            case 403 -> "app may lack required Shopify scopes (e.g. write_products); re-install with updated permissions";
            case 404 -> "check shop domain matches the authorized myshopify.com store";
            default -> "check plugin logs for Shopify response body";
        };
        String snippet = truncate(StringUtils.trimToEmpty(body), 300);
        if (StringUtils.isBlank(snippet)) {
            return "Shopify GraphQL HTTP " + code + ", shopName=" + shopName + "; " + hint;
        }
        return "Shopify GraphQL HTTP " + code + ", shopName=" + shopName + "; " + hint + "; body=" + snippet;
    }

    private static String readBody(java.io.InputStream body) {
        if (body == null) {
            return "";
        }
        try {
            return new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max) + "…";
    }

    public static String normalizeDomain(String shopDomain) {
        String domain = StringUtils.trim(shopDomain);
        domain = StringUtils.removeStartIgnoreCase(domain, "https://");
        domain = StringUtils.removeStartIgnoreCase(domain, "http://");
        domain = StringUtils.removeEnd(domain, "/");
        return domain;
    }
}
