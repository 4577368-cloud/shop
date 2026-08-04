package com.tang.plugin.controller.auth;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.ShopifyProperties;
import com.tang.plugin.service.auth.AuthService;
import com.tang.plugin.service.auth.CookieHelper;
import com.tang.plugin.service.user.ShopifyAuthService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/plugin/shopify/auth")
public class ShopifyAuthController {

    /** Cookie flag: OAuth started via standalone "Login with Shopify". */
    private static final String LOGIN_FLOW_COOKIE = "tb_shopify_login";
    /** Safe relative return path after Shopify login (e.g. /en/products). */
    private static final String LOGIN_RETURN_COOKIE = "tb_login_return_to";

    @Resource
    private ShopifyAuthService shopifyAuthService;
    @Resource
    private ShopifyProperties shopifyProperties;
    @Resource
    private com.tang.plugin.service.user.ShopifySessionTokenService shopifySessionTokenService;
    @Resource
    private AuthService authService;
    @Resource
    private CookieHelper cookieHelper;

    /**
     * Exchange a Shopify App Bridge session token for a Tangbuy Bearer JWT (embedded mode).
     * Public endpoint — authenticity comes from the Shopify-signed session JWT itself.
     */
    @PostMapping("/session-token")
    public ResponseEntity<Map<String, Object>> sessionToken(@RequestBody Map<String, String> body,
                                                            @RequestHeader(value = "X-Tangbuy-Token", required = false)
                                                            String tangbuyToken) {
        String token = body == null ? null : body.get("sessionToken");
        Map<String, Object> result = shopifySessionTokenService.exchange(token, tangbuyToken);
        if ("NEED_OAUTH".equals(String.valueOf(result.get("code")))) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/install")
    public ResponseEntity<Void> install(@RequestParam("shop") String shop,
                                          HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        String redirectUrl = shopifyAuthService.buildInstallUrl(userId, shop);
        log.info("Shopify install redirect shop={} userId={}", shop, userId);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(redirectUrl))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    /**
     * Login-free OAuth start for App Store / Admin embedded install.
     * Public — authenticity of the eventual binding comes from Shopify OAuth HMAC + shop email.
     * Remembers {@code host} in a short-lived cookie so the callback can bounce back into Admin.
     */
    @GetMapping("/install-embedded")
    public ResponseEntity<Void> installEmbedded(@RequestParam("shop") String shop,
                                                  @RequestParam(value = "host", required = false) String host) {
        String redirectUrl = shopifyAuthService.buildInstallUrlAutoProvision(shop);
        log.info("Shopify embedded install redirect shop={} hostPresent={}", shop, StringUtils.isNotBlank(host));
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(redirectUrl))
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (StringUtils.isNotBlank(host)) {
            // Round-trip Admin host across Shopify consent (not returned in OAuth query).
            builder.header(HttpHeaders.SET_COOKIE,
                    "tb_embed_host=" + URLEncoder.encode(host.trim(), StandardCharsets.UTF_8)
                            + "; Path=/; Max-Age=600; HttpOnly; Secure; SameSite=None");
        }
        // Clear any leftover standalone-login markers so callback does not set cookies in Admin.
        builder.header(HttpHeaders.SET_COOKIE, clearCookie(LOGIN_FLOW_COOKIE));
        builder.header(HttpHeaders.SET_COOKIE, clearCookie(LOGIN_RETURN_COOKIE));
        return builder.build();
    }

    /**
     * Standalone "Login with Shopify": public OAuth start that auto-provisions/binds the shop
     * owner and, on callback, sets {@code tb_access}/{@code tb_refresh} cookies.
     *
     * <p>Not for Admin iframe — embedded continues to use session-token / install-embedded.
     *
     * @param returnTo optional relative path (e.g. {@code /en/products}); open-redirect safe
     */
    @GetMapping("/login")
    public ResponseEntity<Void> loginWithShopify(@RequestParam("shop") String shop,
                                                   @RequestParam(value = "return_to", required = false) String returnTo) {
        String redirectUrl = shopifyAuthService.buildInstallUrlAutoProvision(shop);
        String safeReturn = sanitizeReturnTo(returnTo);
        log.info("Shopify standalone login redirect shop={} returnTo={}", shop, safeReturn);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(redirectUrl))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.SET_COOKIE,
                        LOGIN_FLOW_COOKIE + "=1; Path=/; Max-Age=600; HttpOnly; Secure; SameSite=Lax")
                .header(HttpHeaders.SET_COOKIE,
                        LOGIN_RETURN_COOKIE + "="
                                + URLEncoder.encode(safeReturn, StandardCharsets.UTF_8)
                                + "; Path=/; Max-Age=600; HttpOnly; Secure; SameSite=Lax")
                // Avoid bouncing to Admin if a stale embed host cookie remains.
                .header(HttpHeaders.SET_COOKIE, clearCookie("tb_embed_host"))
                .build();
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam("shop") String shop,
                                        HttpServletRequest request) {
        // P2.1: /status is now protected — userId is always injected by JwtAuthFilter.
        // The user-scoped check prevents leaking whether a shop is authorized under
        // another account.
        Long userId = (Long) request.getAttribute("userId");
        return shopifyAuthService.getShopStatus(shop, userId);
    }

    /**
     * Active authorized shops bound to the current user. P2: scoped by user_shop binding.
     * JwtAuthFilter injects {@code userId} into the request attribute.
     */
    @GetMapping("/shops")
    public List<Map<String, Object>> shops(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return shopifyAuthService.listActiveShops(userId);
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(HttpServletRequest request) {
        Map<String, String> params = extractQueryParams(request);
        log.info("Shopify auth callback shop={} queryString={} paramKeys={}",
                params.get("shop"), request.getQueryString(), params.keySet());
        if (params.isEmpty()) {
            throw new CustomException(
                    "Shopify callback params empty. Do NOT open /callback directly. "
                            + "Use /api/plugin/shopify/auth/install?shop=YOUR_SHOP.myshopify.com "
                            + "and ensure Partner App URL is not set to callback. "
                            + "queryString=" + request.getQueryString());
        }
        Map<String, Object> result = shopifyAuthService.handleCallback(params);
        // On success bounce back into the right host:
        // - Embedded (Admin): return to admin.shopify.com/apps/{apiKey} so the merchant
        //   continues inside Admin iframe (not a second standalone "install" tab).
        // - Standalone: SPA /authorize with shop + status for localStorage restore.
        String shopDomain = String.valueOf(result.get("shopDomain"));
        String status = String.valueOf(result.getOrDefault("status", "OK"));
        String base = StringUtils.removeEnd(
                StringUtils.trimToEmpty(shopifyProperties.getFrontendBaseUrl()), "/");
        String host = params.get("host");
        String embedded = params.get("embedded");
        if (StringUtils.isBlank(host)) {
            jakarta.servlet.http.Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (jakarta.servlet.http.Cookie c : cookies) {
                    if ("tb_embed_host".equals(c.getName()) && StringUtils.isNotBlank(c.getValue())) {
                        host = URLDecoder.decode(c.getValue(), StandardCharsets.UTF_8);
                        embedded = "1";
                        break;
                    }
                }
            }
        }

        boolean shopifyLogin = cookieEquals(request, LOGIN_FLOW_COOKIE, "1");
        String loginReturnTo = readCookie(request, LOGIN_RETURN_COOKIE);
        if (StringUtils.isNotBlank(loginReturnTo)) {
            loginReturnTo = URLDecoder.decode(loginReturnTo, StandardCharsets.UTF_8);
        }
        loginReturnTo = sanitizeReturnTo(loginReturnTo);

        String redirectUrl;
        boolean backToAdmin = StringUtils.isNotBlank(host)
                || "1".equals(embedded)
                || "true".equalsIgnoreCase(embedded);
        // Standalone Login with Shopify must not bounce into Admin even if a stale host remains.
        if (shopifyLogin) {
            backToAdmin = false;
        }
        String apiKey = StringUtils.trimToEmpty(shopifyProperties.getApiKey());
        if (backToAdmin && StringUtils.isNotBlank(apiKey) && StringUtils.isNotBlank(shopDomain)) {
            String handle = shopDomain.toLowerCase(java.util.Locale.ROOT)
                    .replace(".myshopify.com", "");
            // Re-open inside Admin on workbench step 1 (authorize).
            // Requires Partner App URL = https://ai.tangbuy.com (not .../en/install),
            // so Admin appends /en/authorize under the app origin.
            redirectUrl = "https://admin.shopify.com/store/" + handle
                    + "/apps/" + apiKey
                    + "/en/authorize";
        } else if (shopifyLogin && "OK".equals(status)) {
            redirectUrl = base + loginReturnTo;
        } else {
            StringBuilder redirect = new StringBuilder(base)
                    .append("/authorize?shop=")
                    .append(URLEncoder.encode(shopDomain, StandardCharsets.UTF_8))
                    .append("&status=")
                    .append(URLEncoder.encode(status, StandardCharsets.UTF_8));
            redirectUrl = redirect.toString();
        }
        log.info("Shopify auth callback result status={} shopDomain={} backToAdmin={} shopifyLogin={}",
                status, shopDomain, backToAdmin, shopifyLogin);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(redirectUrl))
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        builder.header(HttpHeaders.SET_COOKIE,
                "tb_embed_host=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=None");
        builder.header(HttpHeaders.SET_COOKIE, clearCookie(LOGIN_FLOW_COOKIE));
        builder.header(HttpHeaders.SET_COOKIE, clearCookie(LOGIN_RETURN_COOKIE));

        if (shopifyLogin && "OK".equals(status) && !backToAdmin) {
            Object bound = result.get("boundToUserId");
            Long boundUserId = bound instanceof Number ? ((Number) bound).longValue() : null;
            if (boundUserId != null) {
                try {
                    AuthService.AuthResult session = authService.issueSessionForUserId(boundUserId, request);
                    builder.header(HttpHeaders.SET_COOKIE,
                            cookieHelper.buildAccessCookie(session.accessToken()).toString());
                    builder.header(HttpHeaders.SET_COOKIE,
                            cookieHelper.buildRefreshCookie(session.refreshToken()).toString());
                    log.info("Shopify login cookies set for userId={} shopDomain={}", boundUserId, shopDomain);
                } catch (Exception e) {
                    log.error("Shopify login failed to issue session userId={} shopDomain={}",
                            boundUserId, shopDomain, e);
                    // Fall through: still redirect; client will see unauthenticated state.
                }
            }
        }
        return builder.build();
    }

    /** Relative path only — blocks open redirects. */
    private static String sanitizeReturnTo(String returnTo) {
        if (StringUtils.isBlank(returnTo)) {
            return "/en/products";
        }
        String path = returnTo.trim();
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("://")) {
            return "/en/products";
        }
        if (path.length() > 512) {
            return "/en/products";
        }
        return path;
    }

    private static String clearCookie(String name) {
        return name + "=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax";
    }

    private static boolean cookieEquals(HttpServletRequest request, String name, String expected) {
        String value = readCookie(request, name);
        return expected.equals(value);
    }

    private static String readCookie(HttpServletRequest request, String name) {
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie c : cookies) {
            if (name.equals(c.getName()) && StringUtils.isNotBlank(c.getValue())) {
                return c.getValue();
            }
        }
        return null;
    }

    private static Map<String, String> extractQueryParams(HttpServletRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        Map<String, String[]> raw = request.getParameterMap();
        if (raw != null) {
            for (Map.Entry<String, String[]> e : raw.entrySet()) {
                if (e.getValue() != null && e.getValue().length > 0 && StringUtils.isNotBlank(e.getValue()[0])) {
                    params.put(e.getKey(), e.getValue()[0]);
                }
            }
        }
        if (!params.isEmpty()) {
            return params;
        }
        // Fallback: parse raw query string if container did not bind parameters
        String qs = request.getQueryString();
        if (StringUtils.isBlank(qs)) {
            return params;
        }
        for (String pair : qs.split("&")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = urlDecode(pair.substring(0, idx));
            String value = urlDecode(pair.substring(idx + 1));
            if (StringUtils.isNotBlank(key)) {
                params.put(key, value);
            }
        }
        return params;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
