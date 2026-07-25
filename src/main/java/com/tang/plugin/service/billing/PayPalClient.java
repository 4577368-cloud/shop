package com.tang.plugin.service.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.PayPalProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * PayPal REST API 客户端封装。
 *
 * <p>封装三类核心调用：
 * <ol>
 *   <li>{@link #getAccessToken} — Client Credentials 模式获取 access token（缓存 1 小时）</li>
 *   <li>{@link #createOrder} — 创建 PayPal Order（返回 orderId + approval link）</li>
 *   <li>{@link #captureOrder} — 捕获已批准的订单（资金实际入账）</li>
 * </ol>
 *
 * <p>使用 Spring 6 RestClient（Java 17+）。错误统一抛 CustomException，由全局异常处理器转 HTTP 响应。
 */
@Slf4j
@Component
public class PayPalClient {

    @Resource
    private PayPalProperties props;

    /** access token 缓存。字段非线程安全但 PayPal token 容忍并发请求重复获取，无需加锁。 */
    private volatile String cachedAccessToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    // ===== Access Token =====

    /**
     * 获取 PayPal access token（client credentials grant）。
     * 缓存策略：距过期 < 60s 时刷新。
     */
    public String getAccessToken() {
        if (!props.isEnabled()) {
            throw new CustomException("PayPal is not configured", 503, "PAYPAL_NOT_CONFIGURED");
        }
        Instant now = Instant.now();
        if (cachedAccessToken != null && now.isBefore(tokenExpiresAt.minusSeconds(60))) {
            return cachedAccessToken;
        }

        String auth = props.getClientId() + ":" + props.getClientSecret();
        String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));

        RestClient client = RestClient.builder().baseUrl(props.apiBaseUrl()).build();
        try {
            ResponseEntity<JsonNode> resp = client.post()
                    .uri("/v1/oauth2/token")
                    .header("Authorization", "Basic " + encoded)
                    .header("Accept", "application/json")
                    .header("Accept-Language", "en_US")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=client_credentials")
                    .retrieve()
                    .toEntity(JsonNode.class);
            JsonNode body = resp.getBody();
            if (body == null || !body.has("access_token")) {
                throw new CustomException("PayPal token response missing access_token", 502, "PAYPAL_TOKEN_FAILED");
            }
            cachedAccessToken = body.get("access_token").asText();
            long expiresIn = body.has("expires_in") ? body.get("expires_in").asLong() : 3600L;
            tokenExpiresAt = now.plusSeconds(expiresIn);
            log.debug("PayPal access token refreshed, expires in {}s", expiresIn);
            return cachedAccessToken;
        } catch (RestClientResponseException e) {
            log.error("PayPal token request failed: status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new CustomException("PayPal authentication failed", 502, "PAYPAL_TOKEN_FAILED");
        }
    }

    // ===== Create Order =====

    /**
     * 创建 PayPal Order。
     *
     * @param amountUsdCents USD 金额（分）。PayPal API 要求 value 为字符串「X.YY」
     * @param description    订单描述（显示在 PayPal 弹窗）
     * @param customId       自定义标识（用于 webhook 关联，通常是我们的 payment_orders.id）
     * @return PayPal orderID（如 "5O190127TN364715T"）
     */
    public String createOrder(Long amountUsdCents, String description, String customId) {
        if (amountUsdCents == null || amountUsdCents <= 0) {
            throw new CustomException("amountUsdCents must be positive", 400, "INVALID_AMOUNT");
        }
        // 分 → 元字符串。例如 10000 → "100.00"
        String value = String.format("%d.%02d", amountUsdCents / 100, amountUsdCents % 100);

        Map<String, Object> body = Map.of(
                "intent", "CAPTURE",
                "purchase_units", List.of(Map.of(
                        "amount", Map.of(
                                "currency_code", "USD",
                                "value", value
                        ),
                        "description", description != null ? description : "Payment",
                        "custom_id", customId != null ? customId : ""
                ))
        );

        RestClient client = RestClient.builder().baseUrl(props.apiBaseUrl()).build();
        try {
            JsonNode resp = client.post()
                    .uri("/v2/checkout/orders")
                    .header("Authorization", "Bearer " + getAccessToken())
                    .header("Accept", "application/json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (resp == null || !resp.has("id")) {
                throw new CustomException("PayPal create-order response missing id", 502, "PAYPAL_CREATE_FAILED");
            }
            String orderId = resp.get("id").asText();
            log.info("PayPal order created: id={} amount={}cny customId={}", orderId, amountUsdCents, customId);
            return orderId;
        } catch (RestClientResponseException e) {
            log.error("PayPal create-order failed: status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new CustomException("PayPal create-order failed: " + e.getStatusCode(), 502, "PAYPAL_CREATE_FAILED");
        }
    }

    // ===== Capture Order =====

    /**
     * 捕获已批准的 PayPal 订单。资金实际入账。
     *
     * @return CaptureResult 含 captureId 与状态
     */
    public CaptureResult captureOrder(String paypalOrderId) {
        RestClient client = RestClient.builder().baseUrl(props.apiBaseUrl()).build();
        try {
            JsonNode resp = client.post()
                    .uri("/v2/checkout/orders/{id}/capture", paypalOrderId)
                    .header("Authorization", "Bearer " + getAccessToken())
                    .header("Accept", "application/json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            if (resp == null) {
                throw new CustomException("PayPal capture response empty", 502, "PAYPAL_CAPTURE_FAILED");
            }
            String status = resp.has("status") ? resp.get("status").asText() : "UNKNOWN";
            if (!"COMPLETED".equals(status)) {
                throw new CustomException("PayPal capture status not COMPLETED: " + status, 502, "PAYPAL_CAPTURE_INCOMPLETE");
            }
            // 从 purchase_units[0].payments.captures[0] 取 capture id
            String captureId = extractCaptureId(resp);
            log.info("PayPal captured: orderId={} status={} captureId={}", paypalOrderId, status, captureId);
            return new CaptureResult(captureId, status);
        } catch (RestClientResponseException e) {
            log.error("PayPal capture failed: orderId={} status={} body={}",
                    paypalOrderId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new CustomException("PayPal capture failed: " + e.getStatusCode(), 502, "PAYPAL_CAPTURE_FAILED");
        }
    }

    private String extractCaptureId(JsonNode resp) {
        try {
            JsonNode units = resp.path("purchase_units");
            if (units.isArray() && !units.isEmpty()) {
                JsonNode captures = units.get(0).path("payments").path("captures");
                if (captures.isArray() && !captures.isEmpty()) {
                    JsonNode c = captures.get(0);
                    if (c.has("id")) return c.get("id").asText();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract captureId from PayPal response", e);
        }
        return null;
    }

    /**
     * 查询 PayPal 订单详情（孤儿订单自愈用）。
     * 返回订单状态 + captureId（如有）。
     *
     * <p>PayPal 订单状态：CREATED / SAVED / APPROVED / VOIDED / COMPLETED / PAYER_ACTION_REQUIRED
     * 其中 COMPLETED 表示 capture 已完成。
     */
    public OrderStatusResult getOrderStatus(String paypalOrderId) {
        RestClient client = RestClient.builder().baseUrl(props.apiBaseUrl()).build();
        try {
            JsonNode resp = client.get()
                    .uri("/v2/checkout/orders/{id}", paypalOrderId)
                    .header("Authorization", "Bearer " + getAccessToken())
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(JsonNode.class);
            if (resp == null) {
                return new OrderStatusResult("UNKNOWN", null);
            }
            String status = resp.has("status") ? resp.get("status").asText() : "UNKNOWN";
            String captureId = extractCaptureId(resp);
            return new OrderStatusResult(status, captureId);
        } catch (RestClientResponseException e) {
            log.warn("PayPal get-order failed: orderId={} status={} body={}",
                    paypalOrderId, e.getStatusCode(), e.getResponseBodyAsString());
            // 404 表示订单不存在（PayPal 测试环境常见），返回 UNKNOWN 让调用方决定
            if (e.getStatusCode().value() == 404) {
                return new OrderStatusResult("NOT_FOUND", null);
            }
            throw new CustomException("PayPal get-order failed: " + e.getStatusCode(), 502, "PAYPAL_QUERY_FAILED");
        }
    }

    /** 订单状态查询结果。 */
    public record OrderStatusResult(String paypalStatus, String captureId) {}

    /** Capture 结果。 */
    public record CaptureResult(String captureId, String status) {}
}
