package com.tang.plugin.controller.auth;

import com.tang.common.core.exception.CustomException;
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
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/plugin/shopify/auth")
public class ShopifyAuthController {

    @Resource
    private ShopifyAuthService shopifyAuthService;

    @GetMapping("/install")
    public ResponseEntity<Void> install(@RequestParam("shop") String shop) {
        String redirectUrl = shopifyAuthService.buildInstallUrl(shop);
        log.info("Shopify install redirect shop={}", shop);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(redirectUrl))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    @GetMapping("/callback")
    public Map<String, Object> callback(HttpServletRequest request) {
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
        return shopifyAuthService.handleCallback(params);
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
