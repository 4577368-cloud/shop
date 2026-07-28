package com.tang.plugin.config;

import com.tang.plugin.service.auth.CookieHelper;
import com.tang.plugin.service.auth.JwtService;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * JWT auth filter: validates {@code Authorization: Bearer} or {@code tb_access} cookie on
 * protected routes and injects {@code userId} (+ optional {@code shopName}/{@code shopDomain})
 * into request attributes.
 *
 * <p>Protected routes (require login):
 * <ul>
 *   <li>{@code /api/plugin/auth/me}</li>
 *   <li>{@code /api/plugin/auth/change-password}</li>
 *   <li>{@code /api/plugin/shopify/auth/install} — must know who is installing to bind the shop</li>
 *   <li>{@code /api/plugin/shopify/auth/shops} — scoped by user</li>
 *   <li>{@code /api/plugin/shopify/auth/status}</li>
 *   <li>{@code /api/plugin/user/**}</li>
 *   <li>Business APIs under match/order/pricing/…</li>
 * </ul>
 *
 * <p>Public: register/login/refresh, Shopify OAuth callback, {@code /session-token}, webhooks.
 */
@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** Exact paths that require JWT authentication. */
    private static final Set<String> PROTECTED_EXACT_PATHS = Set.of(
            "/api/plugin/auth/me",
            "/api/plugin/auth/change-password",
            "/api/plugin/shopify/auth/install",
            "/api/plugin/shopify/auth/shops",
            "/api/plugin/shopify/auth/status"
    );

    /**
     * Path prefixes that require JWT authentication.
     *
     * <p>SECURITY: All business APIs that accept a {@code shopName} parameter are protected —
     * the caller must be authenticated, and the controller must additionally verify (via
     * {@code ShopAccessGuard}) that the shopName is bound to the calling user. Webhook endpoints
     * are public but verify their own HMAC signature.
     */
    private static final String[] PROTECTED_PREFIXES = {
            "/api/plugin/user/",
            "/api/plugin/billing/",
            "/api/plugin/marketing/",
            // Business APIs (P0 security fix): all of these take a shopName param and must be
            // both authenticated and ownership-checked.
            "/api/plugin/match/",
            "/api/plugin/order/",
            "/api/plugin/pricing/",
            "/api/plugin/logistics/",
            "/api/plugin/catalog/",
            "/api/plugin/product/",
            "/api/plugin/sync/",
            "/api/plugin/sku-align/",
            "/api/plugin/ranking/",
            "/api/plugin/procurement/"
    };

    /**
     * Exact paths that bypass JWT protection even if they fall under a protected prefix.
     */
    private static final Set<String> PUBLIC_EXACT_PATHS = Set.of(
            "/api/plugin/billing/paypal/webhook",
            "/api/plugin/shopify/webhook",
            "/api/plugin/shopify/webhooks",
            "/api/plugin/shopify/webhook/compliance",
            "/api/plugin/shopify/webhooks/compliance",
            "/api/plugin/shopify/auth/session-token",
            "/api/plugin/shopify/auth/install-embedded"
    );

    @Resource
    private JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isProtected(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = readBearerToken(request);
        if (token == null) {
            token = readCookie(request, CookieHelper.ACCESS_COOKIE);
        }
        JwtService.AccessTokenClaims claims = jwtService.parseAccessToken(token);

        if (claims == null || claims.userId() == null) {
            log.warn("JWT auth rejected: uri={} hasBearer={} hasCookie={}",
                    request.getRequestURI(),
                    readBearerToken(request) != null,
                    readCookie(request, CookieHelper.ACCESS_COOKIE) != null);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"status\":\"ERROR\",\"message\":\"Unauthorized: login required\",\"code\":\"UNAUTHENTICATED\"}");
            return;
        }

        request.setAttribute("userId", claims.userId());
        if (claims.shopName() != null && !claims.shopName().isBlank()) {
            request.setAttribute("shopName", claims.shopName());
        }
        if (claims.shopDomain() != null && !claims.shopDomain().isBlank()) {
            request.setAttribute("shopDomain", claims.shopDomain());
        }
        filterChain.doFilter(request, response);
    }

    private boolean isProtected(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (PUBLIC_EXACT_PATHS.contains(uri)) {
            return false;
        }
        if (PROTECTED_EXACT_PATHS.contains(uri)) {
            return true;
        }
        for (String prefix : PROTECTED_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String readBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null) return null;
        String trimmed = header.trim();
        if (trimmed.length() > 7 && trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String value = trimmed.substring(7).trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
