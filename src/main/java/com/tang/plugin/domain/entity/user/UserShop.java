package com.tang.plugin.domain.entity.user;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * Active binding between a Tangbuy platform user and a Shopify shop.
 * Table: user_shop (junction; unbind = physical DELETE).
 */
@Data
@Accessors(chain = true)
public class UserShop {
    private Long id;
    private Long userId;
    private String shopName;
    private String shopDomain;
    /** Reserved for future collaborator roles; currently always "owner". */
    private String role;
    private Instant boundAt;
    private Instant createdAt;
    private Instant updatedAt;
}
