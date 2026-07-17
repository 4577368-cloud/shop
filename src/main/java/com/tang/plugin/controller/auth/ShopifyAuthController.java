package com.tang.plugin.controller.auth;

import com.tang.plugin.service.user.ShopifyAuthService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
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
    public Map<String, Object> callback(@RequestParam Map<String, String> params) {
        log.info("Shopify auth callback shop={}", params.get("shop"));
        return shopifyAuthService.handleCallback(params);
    }
}
