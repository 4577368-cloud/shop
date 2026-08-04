package com.tang.plugin.controller.auth;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.service.user.WooCommerceAuthService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Slf4j
@RestController
@RequestMapping("/api/plugin/woocommerce/auth")
public class WooCommerceAuthController {

    @Resource
    private WooCommerceAuthService wooCommerceAuthService;

    @GetMapping("/start")
    public ResponseEntity<Void> start(@RequestParam("domain") String domain,
                                      HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        String userName = (String) request.getAttribute("userName");
        String redirectUrl = wooCommerceAuthService.buildAuthorizeUrl(domain, userId, userName);
        log.info("WooCommerce auth redirect domain={} userId={}", domain, userId);
        return ResponseEntity.status(302)
                .location(URI.create(redirectUrl))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    @PostMapping("/callback")
    public ResponseEntity<String> callback(@RequestParam("domain") String domain,
                                           @RequestBody String jsonBody) {
        wooCommerceAuthService.handleCallback(domain, jsonBody);
        return ResponseEntity.ok("OAuth authorization processed successfully");
    }

    @GetMapping("/wooRedirect")
    public ResponseEntity<Void> wooRedirect(@RequestParam("domain") String domain) {
        if (domain == null || domain.isBlank()) {
            throw new CustomException("domain is required", 400, "INVALID_DOMAIN");
        }
        return ResponseEntity.status(302)
                .location(URI.create("/en/authorize?woo_domain=" + domain))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
