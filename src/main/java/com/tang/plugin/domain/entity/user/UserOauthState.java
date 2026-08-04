package com.tang.plugin.domain.entity.user;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * Short-lived OAuth state token for Shopify install flow.
 * Binds a state nonce to the user who initiated the install, so the callback
 * can both verify CSRF and attribute the resulting shop binding.
 * Table: user_oauth_state.
 */
@Data
@Accessors(chain = true)
public class UserOauthState {
    private Long id;
    private String stateHash;
    private Long userId;
    private String shopDomain;
    /** STANDALONE | EMBEDDED | LOGIN — drives post-OAuth redirect. */
    private String flow;
    private Instant expiresAt;
    private Instant consumedAt;
    private Instant createdAt;
}
