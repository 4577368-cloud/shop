package com.tang.plugin.config;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "tang.plugin.tangbuy-admin")
public class TangbuyAdminProxyProperties {

    private String baseUrl = "https://admin.tangbuy.cc/prod-api";

    /** Admin Bearer token (same as Vercel TANGBUY_ADMIN_TOKEN). */
    private String token = "";

    public boolean isConfigured() {
        return StringUtils.isNotBlank(token);
    }

    public String resolvedAuthorization() {
        String t = StringUtils.trimToEmpty(token);
        if (StringUtils.startsWithIgnoreCase(t, "Bearer ")) {
            return t;
        }
        return "Bearer " + t;
    }
}
