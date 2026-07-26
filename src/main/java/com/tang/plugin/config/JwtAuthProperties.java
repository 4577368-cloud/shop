package com.tang.plugin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * User auth configuration (JWT + cookie).
 * Bound to prefix {@code tang.plugin.auth}.
 */
@Data
@Component
@ConfigurationProperties(prefix = "tang.plugin.auth")
public class JwtAuthProperties {

    private Jwt jwt = new Jwt();
    private Cookie cookie = new Cookie();
    private RateLimit rateLimit = new RateLimit();
    private PasswordReset passwordReset = new PasswordReset();

    @Data
    public static class Jwt {
        private String secret = "";
        /**
         * Access token TTL. Short-lived (15 min default) so a leaked JWT becomes useless quickly;
         * clients stay logged in via the long-lived refresh token rotation flow.
         */
        private long accessTtlSeconds = 900L;  // 15 minutes
        private long refreshTtlSeconds = 2592000L; // 30 days
    }

    @Data
    public static class Cookie {
        /** Lax | Strict | None. Use "none" only with secure=true over HTTPS (cross-origin). */
        private String sameSite = "Lax";
        private boolean secure = false;
        /** Empty = host-only cookie. Set to ".tangbuy.cc" for cross-subdomain in prod. */
        private String domain = "";
    }

    /**
     * Rate limiting (in-memory, per-IP). Disabled when {@code enabled=false} (e.g. local dev).
     */
    @Data
    public static class RateLimit {
        private boolean enabled = true;
        /** Login: max attempts per window per IP. */
        private int loginMaxAttempts = 5;
        /** Login window in seconds. */
        private long loginWindowSeconds = 60;
        /** Register: max attempts per window per IP. */
        private int registerMaxAttempts = 3;
        /** Register window in seconds. */
        private long registerWindowSeconds = 3600;
        /** Forgot-password: max attempts per window per IP. */
        private int forgotPasswordMaxAttempts = 3;
        /** Forgot-password window in seconds. */
        private long forgotPasswordWindowSeconds = 3600;
    }

    @Data
    public static class PasswordReset {
        /**
         * If true, the forgot-password endpoint returns the raw reset token in the response
         * (dev convenience — lets the local UI skip email delivery).
         * MUST be false in production; production should deliver the token via email.
         */
        private boolean returnRawTokenInDev = false;
    }
}
