package com.tang.plugin.component;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Generates platform-scoped unique keys for outer ids.
 */
@Slf4j
@Component
public class OuterUniqueComponent {

    public String generateShopifyOrderLineUnique(String shopName, String externalLineId) {
        return join("SHOPIFY", shopName, externalLineId);
    }

    public String generateShopifyOrderUnique(String shopName, String externalOrderId) {
        return join("SHOPIFY", shopName, externalOrderId);
    }

    private String join(String platform, String shopName, String outerId) {
        if (StringUtils.isAnyBlank(platform, shopName, outerId)) {
            throw new IllegalArgumentException("unique key parts blank");
        }
        return platform + ":" + shopName + ":" + outerId;
    }
}
