package com.tang.plugin.enums;

import lombok.Getter;

@Getter
public enum PluginType {
    SHOPIFY("SHOPIFY", "Shopify"),
    WOOCOMMERCE("WOOCOMMERCE", "WooCommerce"),
    AMAZON("AMAZON", "Amazon");

    private final String code;
    private final String displayName;

    PluginType(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }
}
