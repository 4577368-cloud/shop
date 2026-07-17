package com.tang.plugin.domain.bo.shopify;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Enabled Shopify shop credentials for the integration layer (not PluginShopBO).
 */
@Data
@Accessors(chain = true)
public class ShopifyEnabledShop {
    private String shopName;
    private String shopDomain;
    private String accessToken;
}
