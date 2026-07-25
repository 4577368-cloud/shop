package com.tang.plugin.config;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "tang.plugin.pipispy")
public class PipispyProperties {

    /** pipispy API key — server only (Render: TANG_PLUGIN_PIPIADS_API_KEY). */
    private String apiKey = "";

    private String dataUrl = "https://www.pipispy.com/open-api/v1/data";

    private String creditsUrl = "https://www.pipispy.com/open-api/v1/credits-balance";

    private int connectTimeoutMs = 8000;

    private int readTimeoutMs = 45000;

    public boolean isConfigured() {
        return StringUtils.isNotBlank(apiKey);
    }
}
