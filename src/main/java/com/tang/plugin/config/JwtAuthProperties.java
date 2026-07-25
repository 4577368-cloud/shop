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

    @Data
    public static class Jwt {
        private String secret = "";
        private long accessTtlSeconds = 604800L;  // 7 days
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
}
