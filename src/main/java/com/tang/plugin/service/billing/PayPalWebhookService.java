package com.tang.plugin.service.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.tang.plugin.config.PayPalProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PayPal Webhook 处理服务：签名校验 + 事件解析。
 *
 * <p>签名校验走 PayPal 官方接口 {@code POST /v1/notifications/verify-webhook-signature}，
 * 不依赖本地证书下载/缓存逻辑（避免实现 CA 链验证的复杂性）。
 *
 * <p>需要的环境变量：{@code TANG_PLUGIN_PAYPAL_WEBHOOK_ID}（在 PayPal Developer 后台创建 webhook 后获得）。
 * 未配置时直接返回校验失败（fail-safe，避免伪造请求造成资金错账）。
 *
 * <p>PayPal 至少一次投递（at-least-once），相同事件可能投递多次，
 * 业务侧通过 {@code paypal_order_id} + {@code paypal_capture_id} 双重幂等。
 */
@Slf4j
@Service
public class PayPalWebhookService {

    /** PayPal webhook 请求头（大小写无关，Spring 自动归一化为大写带连字符）。 */
    public static final String H_TRANSMISSION_ID = "PAYPAL-TRANSMISSION-ID";
    public static final String H_TRANSMISSION_TIME = "PAYPAL-TRANSMISSION-TIME";
    public static final String H_TRANSMISSION_SIG = "PAYPAL-TRANSMISSION-SIG";
    public static final String H_CERT_URL = "PAYPAL-CERT-URL";
    public static final String H_AUTH_ALGO = "PAYPAL-AUTH-ALGO";

    @Resource
    private PayPalProperties props;

    @Resource
    private PayPalClient payPalClient;

    /**
     * 调 PayPal 校验接口，验证 webhook 请求是否真来自 PayPal。
     *
     * <p>需要 {@code props.getWebhookId()} 非空，否则视为校验失败。
     *
     * @param headers HTTP 请求头（用于取出 5 个 PAYPAL-* 头）
     * @param rawBody 原始请求体字符串（与 PayPal 签名时的字节流一致）
     * @return true=校验通过；false=校验失败或未配置 webhook_id
     */
    public boolean verifySignature(HttpHeaders headers, String rawBody) {
        if (!props.isEnabled()) {
            log.warn("PayPal not enabled; rejecting webhook");
            return false;
        }
        String webhookId = props.getWebhookId();
        if (webhookId == null || webhookId.isBlank()) {
            log.warn("PayPal webhook_id not configured; rejecting webhook (fail-safe)");
            return false;
        }

        String transmissionId = headerOrNull(headers, H_TRANSMISSION_ID);
        String transmissionTime = headerOrNull(headers, H_TRANSMISSION_TIME);
        String transmissionSig = headerOrNull(headers, H_TRANSMISSION_SIG);
        String certUrl = headerOrNull(headers, H_CERT_URL);
        String authAlgo = headerOrNull(headers, H_AUTH_ALGO);

        if (transmissionId == null || transmissionSig == null || certUrl == null || authAlgo == null) {
            log.warn("PayPal webhook missing required headers: id={} sig={} cert={} algo={}",
                    transmissionId, transmissionSig != null, certUrl != null, authAlgo != null);
            return false;
        }

        // 构造校验请求体：webhook_event 必须是原始事件 JSON 对象（不能重新序列化，
        // 否则字段顺序/空白变化会导致签名不匹配）。这里用 LinkedHashMap 把已解析 JSON 装回去。
        Object webhookEvent;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            webhookEvent = mapper.readTree(rawBody);
        } catch (Exception e) {
            log.warn("Failed to parse webhook body as JSON for verification: {}", e.getMessage());
            return false;
        }

        Map<String, Object> verifyBody = new LinkedHashMap<>();
        verifyBody.put("transmission_id", transmissionId);
        verifyBody.put("transmission_time", transmissionTime);
        verifyBody.put("cert_url", certUrl);
        verifyBody.put("auth_algo", authAlgo);
        verifyBody.put("transmission_sig", transmissionSig);
        verifyBody.put("webhook_id", webhookId);
        verifyBody.put("webhook_event", webhookEvent);

        RestClient client = RestClient.builder().baseUrl(props.apiBaseUrl()).build();
        try {
            ResponseEntity<JsonNode> resp = client.post()
                    .uri("/v1/notifications/verify-webhook-signature")
                    .header("Authorization", "Bearer " + payPalClient.getAccessToken())
                    .header("Accept", "application/json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(verifyBody)
                    .retrieve()
                    .toEntity(JsonNode.class);
            JsonNode body = resp.getBody();
            if (body == null || !body.has("verification_status")) {
                log.warn("PayPal verify-webhook-signature returned empty body");
                return false;
            }
            String status = body.get("verification_status").asText();
            boolean ok = "SUCCESS".equalsIgnoreCase(status);
            if (!ok) {
                log.warn("PayPal webhook signature verification failed: status={}", status);
            }
            return ok;
        } catch (RestClientResponseException e) {
            // 4xx/5xx from PayPal verify API：4xx 视为永久失败（签名无效），5xx 视为瞬时错误（让 PayPal 重试）
            if (e.getStatusCode().is5xxServerError()) {
                log.error("PayPal verify-webhook-signature 5xx (transient, will retry): status={} body={}",
                        e.getStatusCode(), e.getResponseBodyAsString());
                throw new RuntimeException("PayPal verify API 5xx: " + e.getStatusCode(), e);
            }
            log.error("PayPal verify-webhook-signature HTTP error: status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            // 网络瞬断等：让 PayPal 重试（max 25 次 / 3 天）
            log.error("PayPal verify-webhook-signature transient error (will retry)", e);
            throw new RuntimeException("PayPal verify API unreachable: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 webhook 事件，提取业务关心的字段。
     *
     * <p>PayPal v2 webhook 事件结构（关键路径）：
     * <pre>
     * {
     *   "id": "WH-2WR32451HC0233532-67976317FL4543714",
     *   "event_type": "PAYMENT.CAPTURE.COMPLETED",
     *   "resource_type": "capture",
     *   "resource": {
     *     "id": "92C10696TN364715T",          // capture_id
     *     "amount": { "currency_code": "USD", "value": "100.00" },
     *     "supplementary_data": {
     *       "related_ids": { "order_id": "5O190127TN364715T" }   // paypal_order_id
     *     }
     *   }
     * }
     * </pre>
     *
     * <p>注：早期版本资源结构略有不同（{@code resource.parent_payment}），这里两种都尝试。
     */
    public WebhookEvent parseEvent(String rawBody) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            JsonNode root = mapper.readTree(rawBody);
            String eventType = textOrNull(root, "event_type");
            String eventId = textOrNull(root, "id");

            JsonNode resource = root.path("resource");
            String captureId = textOrNull(resource, "id");

            // 优先：supplementary_data.related_ids.order_id
            String paypalOrderId = null;
            JsonNode relatedIds = resource.path("supplementary_data").path("related_ids");
            if (relatedIds.has("order_id")) {
                paypalOrderId = relatedIds.get("order_id").asText();
            }
            // 兜底：parent_payment（老格式）
            if (paypalOrderId == null && resource.has("parent_payment")) {
                paypalOrderId = resource.get("parent_payment").asText();
            }

            // 金额解析（仅用于日志/校验，不参与业务计算）
            Long amountUsdCents = null;
            JsonNode amount = resource.path("amount");
            if (amount.has("value")) {
                try {
                    double usd = Double.parseDouble(amount.get("value").asText());
                    amountUsdCents = Math.round(usd * 100);
                } catch (NumberFormatException ignored) {}
            }

            return new WebhookEvent(eventId, eventType, captureId, paypalOrderId, amountUsdCents);
        } catch (Exception e) {
            log.warn("Failed to parse webhook event: {}", e.getMessage());
            return new WebhookEvent(null, null, null, null, null);
        }
    }

    private String headerOrNull(HttpHeaders headers, String name) {
        String v = headers.getFirst(name);
        return (v == null || v.isBlank()) ? null : v;
    }

    private String textOrNull(JsonNode node, String field) {
        if (node != null && node.has(field)) {
            JsonNode v = node.get(field);
            if (!v.isNull()) return v.asText();
        }
        return null;
    }

    /**
     * 解析后的 webhook 事件。{@code eventType=null} 表示解析失败。
     *
     * @param eventId        PayPal 事件 ID（用于日志）
     * @param eventType      事件类型，如 PAYMENT.CAPTURE.COMPLETED
     * @param captureId      capture.id（资源 ID）
     * @param paypalOrderId  关联的 PayPal order ID（业务主键）
     * @param amountUsdCents 金额（USD 分，仅用于校验/日志）
     */
    public record WebhookEvent(
            String eventId,
            String eventType,
            String captureId,
            String paypalOrderId,
            Long amountUsdCents
    ) {}
}
