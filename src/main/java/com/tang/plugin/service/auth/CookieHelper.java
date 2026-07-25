package com.tang.plugin.service.auth;

import com.tang.plugin.config.JwtAuthProperties;
import jakarta.annotation.Resource;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Builds httpOnly cookies for JWT access + refresh tokens.
 * Access cookie: Path=/ (all routes), short-lived.
 * Refresh cookie: Path=/api/plugin/auth/ (covers refresh + logout so the server can read
 *   and revoke the token on logout; never exposed to non-auth routes by path scoping).
 */
@Component
public class CookieHelper {

    public static final String ACCESS_COOKIE = "tb_access";
    public static final String REFRESH_COOKIE = "tb_refresh";

    /** Refresh cookie path scope: must cover all endpoints that need to read it. */
    public static final String REFRESH_COOKIE_PATH = "/api/plugin/auth/";

    @Resource
    private JwtAuthProperties properties;

    public ResponseCookie buildAccessCookie(String accessToken) {
        return buildBase(ACCESS_COOKIE, accessToken, properties.getJwt().getAccessTtlSeconds(), "/")
                .build();
    }

    public ResponseCookie buildRefreshCookie(String refreshToken) {
        return buildBase(REFRESH_COOKIE, refreshToken, properties.getJwt().getRefreshTtlSeconds(),
                REFRESH_COOKIE_PATH)
                .build();
    }

    public ResponseCookie clearAccessCookie() {
        return buildBase(ACCESS_COOKIE, "", 0, "/").build();
    }

    public ResponseCookie clearRefreshCookie() {
        return buildBase(REFRESH_COOKIE, "", 0, REFRESH_COOKIE_PATH).build();
    }

    private ResponseCookie.ResponseCookieBuilder buildBase(String name, String value, long maxAgeSeconds, String path) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(properties.getCookie().isSecure())
                .sameSite(properties.getCookie().getSameSite())
                .path(path)
                .maxAge(Duration.ofSeconds(maxAgeSeconds));
        if (properties.getCookie().getDomain() != null && !properties.getCookie().getDomain().isBlank()) {
            b.domain(properties.getCookie().getDomain());
        }
        return b;
    }
}
