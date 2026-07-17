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
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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

        try {
            String raw = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Shopify-Access-Token", accessToken)
                    .body(body.toJSONString())
                    .retrieve()
                    .body(String.class);

            JSONObject response = JSON.parseObject(raw);
            if (response == null) {
                throw new CustomException("Shopify GraphQL empty response, shopName=" + shopName);
            }
            if (response.containsKey("errors")) {
                log.error("Shopify GraphQL errors shopName={} errors={}", shopName, response.get("errors"));
                throw new CustomException("Shopify GraphQL errors, shopName=" + shopName);
            }
            return response;
        } catch (RestClientException e) {
            log.error("Shopify GraphQL HTTP failed shopName={} domain={}", shopName, normalizedDomain, e);
            throw new CustomException("Shopify GraphQL HTTP failed, shopName=" + shopName, e);
        }
    }

    public static String normalizeDomain(String shopDomain) {
        String domain = StringUtils.trim(shopDomain);
        domain = StringUtils.removeStartIgnoreCase(domain, "https://");
        domain = StringUtils.removeStartIgnoreCase(domain, "http://");
        domain = StringUtils.removeEnd(domain, "/");
        return domain;
    }
}
