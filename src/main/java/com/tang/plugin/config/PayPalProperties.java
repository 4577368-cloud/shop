package com.tang.plugin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * PayPal 商户凭证与端点配置。
 *
 * <p>本地开发用 sandbox 凭证；生产用 live 凭证。
 * 通过 {@code TANG_PLUGIN_PAYPAL_MODE} 切换 sandbox / live。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "tang.plugin.paypal")
public class PayPalProperties {

    /** sandbox / live。默认 sandbox。 */
    private String mode = "sandbox";

    /** PayPal REST App Client ID（与浏览器 NEXT_PUBLIC_PAYPAL_CLIENT_ID 同值）。 */
    private String clientId = "";

    /** PayPal REST App Secret（仅后端持有，永不暴露给浏览器）。 */
    private String clientSecret = "";

    /**
     * 余额充值时的 USD → CNY 汇率（默认 6.43）。
     * 仅用于 PayPal 充值入账时的换算；不用于订单计价（订单本身已有 USD 金额）。
     */
    private double usdToCnyRate = 6.43;

    /** 可选：PayPal Webhook ID（用于 webhook 签名校验，P3.3 启用）。 */
    private String webhookId = "";

    /**
     * PayPal API base URL。根据 mode 自动选择：
     * sandbox → https://api-m.sandbox.paypal.com
     * live    → https://api-m.paypal.com
     */
    public String apiBaseUrl() {
        return "live".equalsIgnoreCase(mode)
                ? "https://api-m.paypal.com"
                : "https://api-m.sandbox.paypal.com";
    }

    /** 是否启用 PayPal（client_id 与 secret 都非空才算启用）。 */
    public boolean isEnabled() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }
}
