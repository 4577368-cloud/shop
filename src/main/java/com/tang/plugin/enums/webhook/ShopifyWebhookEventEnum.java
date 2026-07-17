package com.tang.plugin.enums.webhook;

import java.util.Arrays;
import java.util.List;

/**
 * Shopify webhook topics. products/update kept for extension but not registered by default.
 */
public enum ShopifyWebhookEventEnum {
    ORDERS_CREATE("orders/create", "ORDERS_CREATE", true),
    ORDERS_UPDATED("orders/updated", "ORDERS_UPDATED", true),
    APP_UNINSTALLED("app/uninstalled", "APP_UNINSTALLED", true),
    /** Skeleton only — phase-2 does not register */
    PRODUCTS_UPDATE("products/update", "PRODUCTS_UPDATE", false);

    private final String topic;
    private final String graphqlTopic;
    private final boolean registerOnAuth;

    ShopifyWebhookEventEnum(String topic, String graphqlTopic, boolean registerOnAuth) {
        this.topic = topic;
        this.graphqlTopic = graphqlTopic;
        this.registerOnAuth = registerOnAuth;
    }

    public String getTopic() {
        return topic;
    }

    public String getGraphqlTopic() {
        return graphqlTopic;
    }

    public boolean isRegisterOnAuth() {
        return registerOnAuth;
    }

    public static ShopifyWebhookEventEnum fromTopic(String topic) {
        if (topic == null) {
            return null;
        }
        for (ShopifyWebhookEventEnum value : values()) {
            if (value.topic.equalsIgnoreCase(topic)) {
                return value;
            }
        }
        return null;
    }

    public static List<ShopifyWebhookEventEnum> registerOnAuth() {
        return Arrays.stream(values()).filter(ShopifyWebhookEventEnum::isRegisterOnAuth).toList();
    }
}
