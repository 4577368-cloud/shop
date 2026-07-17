package com.tang.plugin.domain.entity.user;

import com.tang.plugin.enums.ShopifyAuthStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * Shopify store OAuth token — maps to shopify_store_auth.
 */
@Data
@Accessors(chain = true)
public class ShopifyStoreAuth {
    private Long id;
    private String shopName;
    private String shopDomain;
    private String accessToken;
    private String scope;
    private ShopifyAuthStatus status;
    private Instant authorizedAt;
    private Instant updatedAt;
    private Integer delFlag;
}
