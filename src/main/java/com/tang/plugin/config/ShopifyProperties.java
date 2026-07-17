package com.tang.plugin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "tang.plugin.shopify")
public class ShopifyProperties {

    /** Admin API version segment, e.g. 2025-01 */
    private String apiVersion = "2025-01";

    private String apiKey = "";
    private String apiSecret = "";
    private String scopes = "read_orders,write_orders";
    /** OAuth callback, e.g. https://host/api/plugin/shopify/auth/callback */
    private String redirectUri = "http://localhost:8088/api/plugin/shopify/auth/callback";
    /** Public base for webhook callback, e.g. https://host */
    private String webhookBaseUrl = "http://localhost:8088";

    private Polling polling = new Polling();
    private Webhook webhook = new Webhook();

    /** Local-dev fallback only; Provider prefers JDBC ACTIVE rows */
    private List<TestShop> testShops = new ArrayList<>();

    @Data
    public static class Polling {
        private boolean enabled = false;
        private long fixedDelayMs = 300_000L;
    }

    @Data
    public static class Webhook {
        /** products/update subscription — phase-2 default off */
        private boolean registerProductsUpdate = false;
    }

    @Data
    public static class TestShop {
        private String shopName;
        private String shopDomain;
        private String accessToken;
    }
}
