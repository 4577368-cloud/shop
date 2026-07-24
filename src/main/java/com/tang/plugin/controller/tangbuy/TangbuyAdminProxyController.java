package com.tang.plugin.controller.tangbuy;

import com.tang.plugin.config.TangbuyAdminProxyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Forwards Tangbuy admin prod-api calls from Vercel (cannot reach admin.tangbuy.cc directly).
 * Paths mirror {@code https://admin.tangbuy.cc/prod-api/**}.
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/tangbuy-admin")
@RequiredArgsConstructor
public class TangbuyAdminProxyController {

    private final TangbuyAdminProxyProperties props;

    @PostMapping("/product-mall/admin/preferred/pool/add")
    public ResponseEntity<String> preferredPoolAdd(@RequestBody String body) {
        return forward("/product-mall/admin/preferred/pool/add", body);
    }

    @PostMapping("/product-mall/admin/es/product/pageInfo")
    public ResponseEntity<String> productPageInfo(@RequestBody String body) {
        return forward("/product-mall/admin/es/product/pageInfo", body);
    }

    private ResponseEntity<String> forward(String path, String body) {
        if (!props.isConfigured()) {
            return ResponseEntity.status(503).body("{\"msg\":\"TANG_PLUGIN_TANGBUY_ADMIN_TOKEN not configured\"}");
        }
        String url = StringUtils.removeEnd(props.getBaseUrl(), "/") + path;
        try {
            String raw = RestClient.create()
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, props.resolvedAuthorization())
                    .accept(MediaType.APPLICATION_JSON)
                    .body(StringUtils.defaultString(body, "{}"))
                    .retrieve()
                    .body(String.class);
            return ResponseEntity.ok(StringUtils.defaultString(raw, "{}"));
        } catch (RestClientException e) {
            log.error("Tangbuy admin proxy failed path={} url={}", path, url, e);
            return ResponseEntity.status(502).body("{\"msg\":\"" + e.getMessage() + "\"}");
        }
    }
}
