package com.tang.plugin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tangbuy Admin mall (product-mall ES) client settings. When {@code authorization} is blank the
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
     * {@code TANG_PLUGIN_TANGBUY_MALL_TOKEN}.
     */
    private String authorization = "";

    private int connectTimeoutMs = 5_000;
    private int readTimeoutMs = 20_000;
}
