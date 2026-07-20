package com.tang.plugin.config;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tangbuy Admin mall (product-mall ES) client settings. When {@code token} is blank the
 * catalog service keeps the offline JSON fallback.
 */
@Data
@Component
@ConfigurationProperties(prefix = "tang.plugin.tangbuy-mall")
public class TangbuyMallProperties {

    /** Admin origin, e.g. https://admin.tangbuy.cc */
    private String baseUrl = "https://admin.tangbuy.cc";

    /**
     * Bearer token body only (without the {@code Bearer } prefix). Sourced from
     * {@code TANG_PLUGIN_TANGBUY_MALL_TOKEN}. Avoid naming this field {@code authorization}
     * — some runtimes treat that key specially.
     */
    private String token = "";

    private int connectTimeoutMs = 5_000;
    private int readTimeoutMs = 20_000;

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
