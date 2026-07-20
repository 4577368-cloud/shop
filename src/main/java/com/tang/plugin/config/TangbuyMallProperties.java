package com.tang.plugin.config;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tangbuy mall catalog client settings. When {@code token} is blank the catalog service keeps the
 * offline JSON fallback.
 *
 * <p>Default {@code source=gateway} uses {@code tangbuy.cc/gateway/plugin/item/allSubScriptionSearch}
 * (reachable from Render). {@code source=admin} uses Admin ES {@code pageInfo} (often blocked off-VPN).
 */
@Data
@Component
@ConfigurationProperties(prefix = "tang.plugin.tangbuy-mall")
public class TangbuyMallProperties {

    /** {@code gateway} (default) or {@code admin}. */
    private String source = "gateway";

    /** Portal gateway origin, e.g. https://tangbuy.cc */
    private String gatewayBaseUrl = "https://tangbuy.cc";

    /** Admin origin for legacy pageInfo, e.g. https://admin.tangbuy.cc */
    private String baseUrl = "https://admin.tangbuy.cc";

    /**
     * Bearer token body only (portal JWT for gateway; Admin token for admin pageInfo).
     * Sourced from {@code TANG_PLUGIN_TANGBUY_MALL_TOKEN}.
     */
    private String token = "";

    private int connectTimeoutMs = 5_000;
    private int readTimeoutMs = 20_000;

    public boolean useGateway() {
        return !"admin".equalsIgnoreCase(StringUtils.trimToEmpty(source));
    }

    public String resolvedSource() {
        return useGateway() ? "gateway" : "admin";
    }

    /** Trim + strip accidental surrounding quotes / Bearer prefix from Render paste. */
    public String resolvedToken() {
        String t = StringUtils.trimToEmpty(token);
        if (t.length() >= 2) {
            char a = t.charAt(0);
            char b = t.charAt(t.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                t = t.substring(1, t.length() - 1).trim();
            }
        }
        if (StringUtils.startsWithIgnoreCase(t, "Bearer ")) {
            t = t.substring(7).trim();
        }
        return t;
    }
}
