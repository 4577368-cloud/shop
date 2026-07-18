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
    private Product product = new Product();

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
    public static class Product {
        /** products connection page size (cursor pagination). */
        private int pageSize = 50;
        /** variants(first:) — no deep nested pagination in P1. */
        private int variantsFirst = 100;
        /** media(first:) — no deep nested pagination in P1. */
        private int mediaFirst = 50;
        /** Product sync polling — default off in P1. */
        private boolean pollingEnabled = false;
        private long pollingFixedDelayMs = 600_000L;
        /** Incremental window minutes for polling updated_at lower bound. */
        private long pollingWindowMinutes = 60L;
        /** Safety cap on products pages. */
        private int maxPages = 50;
    }

    @Data
    public static class TestShop {
        private String shopName;
        private String shopDomain;
        private String accessToken;
    }
}
