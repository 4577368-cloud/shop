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
        String redirectUrl = base + "/authorize?shop="
                + URLEncoder.encode(shopDomain, StandardCharsets.UTF_8)
                + "&status=" + URLEncoder.encode(status, StandardCharsets.UTF_8);
        log.info("Shopify auth callback result status={} shopDomain={}", status, shopDomain);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(redirectUrl))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
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
