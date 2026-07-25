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
 * JWT auth filter: validates the {@code tb_access} cookie on protected routes and injects
 * {@code userId} into the request attribute. Unprotected routes pass through untouched.
 *
 * <p>Protected routes (require login):
 * <ul>
 *   <li>{@code /api/plugin/auth/me}</li>
 *   <li>{@code /api/plugin/auth/change-password}</li>
 *   <li>{@code /api/plugin/shopify/auth/install} — must know who is installing to bind the shop</li>
 *   <li>{@code /api/plugin/shopify/auth/shops} — scoped by user</li>
 *   <li>{@code /api/plugin/user/**} (future)</li>
 *   <li>{@code /api/plugin/billing/**} (future)</li>
 * </ul>
 *
 * <p>Public routes (no JWT needed): register, login, logout, refresh, Shopify OAuth callback/status,
 * webhooks, all existing business APIs (Shopify sync, match, pricing, logistics, etc.).
 *
 * <p>Note: {@code logout} is intentionally public so users with an expired access token can
 * still clear cookies. The endpoint is idempotent — missing refresh token is a no-op.
 * {@code /callback} is public because it is reached via browser redirect from Shopify; the state
 * nonce (validated server-side) carries the user binding — no JWT is needed at that point.
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
     * Path prefixes that require JWT authentication (for future phases).
     * Webhook endpoints are public but verify their own signature (PayPal Cert-Webhook).
     */
    private static final String[] PROTECTED_PREFIXES = {
            "/api/plugin/user/",
            "/api/plugin/billing/",
            "/api/plugin/marketing/"
    };

    /**
     * Exact paths that bypass JWT protection even if they fall under a protected prefix.
     * Currently: PayPal webhook (under /billing/ but called by PayPal, not browsers).
     */
    private static final Set<String> PUBLIC_EXACT_PATHS = Set.of(
            "/api/plugin/billing/paypal/webhook"
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

        String token = readCookie(request, CookieHelper.ACCESS_COOKIE);
        Long userId = jwtService.verifyAccessToken(token);

        if (userId == null) {
            log.warn("JWT auth rejected: uri={} hasCookie={}", request.getRequestURI(), token != null);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"status\":\"ERROR\",\"message\":\"Unauthorized: login required\",\"code\":\"UNAUTHENTICATED\"}");
            return;
        }

        request.setAttribute("userId", userId);
        filterChain.doFilter(request, response);
    }

    private boolean isProtected(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // Explicit public path (e.g. PayPal webhook) bypasses everything.
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
