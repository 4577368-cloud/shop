package com.tang.plugin.dto.user;

/**
 * User-shop binding DTOs (Java records).
 */
public final class UserShopDtos {

    private UserShopDtos() {}

    /**
     * Summary of a shop bound to the current user. Non-sensitive fields only
     * (never includes access_token).
     */
    public record UserShopResponse(
            String shopName,
            String shopDomain,
            String authStatus,
            java.time.Instant authorizedAt,
            java.time.Instant boundAt,
            Integer productCount
    ) {}

    public record UnbindResponse(String shopName, boolean unbound) {}
}
