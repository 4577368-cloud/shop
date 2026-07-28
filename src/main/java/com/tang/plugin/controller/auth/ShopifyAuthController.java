package com.tang.plugin.controller.auth;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.ShopifyProperties;
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

    @Resource
    private ShopifyAuthService shopifyAuthService;
    @Resource
    private ShopifyProperties shopifyProperties;
    @Resource
    private com.tang.plugin.service.user.ShopifySessionTokenService shopifySessionTokenService;

    /**
     * Exchange a Shopify App Bridge session token for a Tangbuy Bearer JWT (embedded mode).
     * Public endpoint — authenticity comes from the Shopify-signed session JWT itself.
     */
    @PostMapping("/session-token")
    public ResponseEntity<Map<String, Object>> sessionToken(@RequestBody Map<String, String> body) {
        String token = body == null ? null : body.get("sessionToken");
        Map<String, Object> result = shopifySessionTokenService.exchange(token);
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
        return builder.build();
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
        // On success redirect back to the frontend authorize page so the SPA can restore state
        // (localStorage + /status). Failures still surface as JSON via the thrown exception above.
        String shopDomain = String.valueOf(result.get("shopDomain"));
        String status = String.valueOf(result.getOrDefault("status", "OK"));
        String base = StringUtils.removeEnd(
                StringUtils.trimToEmpty(shopifyProperties.getFrontendBaseUrl()), "/");
        // Forward status so the frontend can show the "already bound" notice without an extra API call.
        // Optional host/embedded query (passed through install state in a later iteration) keeps
        // Admin iframe context when present on the callback request.
        String host = params.get("host");
        String embedded = params.get("embedded");
        if (StringUtils.isBlank(host)) {
            // Recover host remembered by /install-embedded
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
        StringBuilder redirect = new StringBuilder(base)
                .append("/authorize?shop=")
                .append(URLEncoder.encode(shopDomain, StandardCharsets.UTF_8))
                .append("&status=")
                .append(URLEncoder.encode(status, StandardCharsets.UTF_8));
        if (StringUtils.isNotBlank(host)) {
            redirect.append("&host=").append(URLEncoder.encode(host, StandardCharsets.UTF_8))
                    .append("&embedded=1");
        } else if ("1".equals(embedded) || "true".equalsIgnoreCase(embedded)) {
            redirect.append("&embedded=1");
        }
        String redirectUrl = redirect.toString();
        log.info("Shopify auth callback result status={} shopDomain={} embedded={}",
                status, shopDomain, StringUtils.isNotBlank(host));
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(redirectUrl))
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        // Clear embed host cookie after use
        builder.header(HttpHeaders.SET_COOKIE,
                "tb_embed_host=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=None");
        return builder.build();
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
