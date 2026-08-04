package com.tang.plugin.config;

import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.constant.AuthConstant;
import com.tang.common.core.domain.UserDto;
import com.tang.common.core.enums.auth.IdentityEnum;
import com.tang.common.service.context.UserContext;
import com.tang.plugin.service.auth.CookieHelper;
import com.tang.plugin.service.auth.JwtService;
import com.tang.plugin.service.auth.PlatformTokenService;
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
 * <p>Public: register/login/refresh, Shopify OAuth callback, {@code /session-token},
 * {@code /install-embedded}, {@code /login} (Login with Shopify), webhooks.
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
            "/api/plugin/shopify/auth/status",
            "/api/plugin/woocommerce/auth/start"
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
            "/api/plugin/shopify/auth/install-embedded",
            "/api/plugin/shopify/auth/login"
    );

    @Resource
    private JwtService jwtService;
    @Resource
    private PlatformTokenService platformTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isProtected(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        UserDto gatewayUser = readGatewayUser(request);
        if (gatewayUser != null) {
            applyRequestAttributes(request, gatewayUser);
            withUserContext(gatewayUser, () -> filterChain.doFilter(request, response));
            return;
        }

        String token = readBearerToken(request);
        if (token == null) {
            token = readCookie(request, CookieHelper.ACCESS_COOKIE);
        }
        if (token == null) {
            token = readCookie(request, "TANGBUY_TOKEN");
        }
        JwtService.AccessTokenClaims claims = jwtService.parseAccessToken(token);
        PlatformTokenService.PlatformUser platformUser = claims == null
                ? platformTokenService.verify(token)
                : null;

        Long userId = claims == null ? null : claims.userId();
        if (userId == null && platformUser != null) {
            userId = platformUser.getUserId();
        }

        if (userId == null) {
            log.warn("JWT auth rejected: uri={} hasBearer={} hasCookie={}",
                    request.getRequestURI(),
                    readBearerToken(request) != null,
                    readCookie(request, CookieHelper.ACCESS_COOKIE) != null
                            || readCookie(request, "TANGBUY_TOKEN") != null);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"status\":\"ERROR\",\"message\":\"Unauthorized: login required\",\"code\":\"UNAUTHENTICATED\"}");
            return;
        }

        UserDto user = new UserDto();
        user.setId(userId);
        user.setUserId(userId);
        user.setIdentity(IdentityEnum.user);
        if (platformUser != null) {
            user.setUser_name(platformUser.getUserName());
            request.setAttribute("email", platformUser.getEmail());
        }
        applyRequestAttributes(request, user);
        if (claims != null && claims.shopName() != null && !claims.shopName().isBlank()) {
            request.setAttribute("shopName", claims.shopName());
        }
        if (claims != null && claims.shopDomain() != null && !claims.shopDomain().isBlank()) {
            request.setAttribute("shopDomain", claims.shopDomain());
        }
        withUserContext(user, () -> filterChain.doFilter(request, response));
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

    private UserDto readGatewayUser(HttpServletRequest request) {
        String raw = request.getHeader(AuthConstant.USER_TOKEN_HEADER);
        if (raw == null || raw.isBlank()) {
            raw = request.getHeader(AuthConstant.USER_STR_TOKEN_HEADER);
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            UserDto user = JSONObject.parseObject(raw, UserDto.class);
            Long userId = user.getId() != null ? user.getId() : user.getUserId();
            if (userId == null) {
                log.warn("Gateway user ignored: missing user id, uri={}", request.getRequestURI());
                return null;
            }
            user.setId(userId);
            user.setUserId(userId);
            if (user.getIdentity() == null) {
                user.setIdentity(IdentityEnum.user);
            }
            return user;
        } catch (Exception e) {
            log.warn("Gateway user ignored: invalid payload, uri={}", request.getRequestURI());
            return null;
        }
    }

    private void applyRequestAttributes(HttpServletRequest request, UserDto user) {
        request.setAttribute("userId", user.getId() != null ? user.getId() : user.getUserId());
        if (user.getUser_name() != null && !user.getUser_name().isBlank()) {
            request.setAttribute("userName", user.getUser_name());
        }
    }

    private void withUserContext(UserDto user, FilterAction action) throws ServletException, IOException {
        UserContext.set(user);
        try {
            action.run();
        } finally {
            UserContext.remove();
        }
    }

    @FunctionalInterface
    private interface FilterAction {
        void run() throws ServletException, IOException;
    }
}
