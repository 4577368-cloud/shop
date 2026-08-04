package com.tang.plugin.service.auth;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Service
public class PlatformTokenService {

    @Value("${tang.plugin.platform.gateway-base-url:https://tangbuy.cc/gateway}")
    private String gatewayBaseUrl;

    public PlatformUser verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Response response = RestClient.create(normalizeBaseUrl() + "/user/getUserInfo")
                    .get()
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header("currency", "CNY")
                    .header("device", "pc")
                    .header("lang", "cn")
                    .header("tang-request-device", "web")
                    .header("tang-request-render", "csr")
                    .header("tang-request-rewrite", "true")
                    .header("x-request-platform", "[]")
                    .header("x-request-store", "[]")
                    .header("x-timezone", "8")
                    .header("x-timezone-id", "Asia/Shanghai")
                    .retrieve()
                    .body(Response.class);
            if (response == null || (response.code != 0 && response.code != 200) || response.data == null) {
                log.warn("Platform token verify failed: responseCode={} hasData={}",
                        response == null ? null : response.code,
                        response != null && response.data != null);
                return null;
            }
            if (response.data.userId == null) {
                log.warn("Platform token verify failed: userId missing userName={} email={}",
                        response.data.userName, response.data.email);
                return null;
            }
            return response.data;
        } catch (RestClientException e) {
            log.warn("Platform token verify request failed: {}", e.getMessage());
            return null;
        }
    }

    private String normalizeBaseUrl() {
        return gatewayBaseUrl == null ? "" : gatewayBaseUrl.replaceAll("/+$", "");
    }

    @Data
    private static class Response {
        private int code;
        private PlatformUser data;
    }

    @Data
    public static class PlatformUser {
        @JsonAlias({"id", "user_id"})
        private Long userId;
        @JsonAlias("user_name")
        private String userName;
        private String email;
    }
}
