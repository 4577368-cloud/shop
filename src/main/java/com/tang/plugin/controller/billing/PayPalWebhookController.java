package com.tang.plugin.controller.billing;

import com.tang.plugin.service.billing.BillingService;
import com.tang.plugin.service.billing.PayPalWebhookService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PayPal Webhook 端点（P3.3）。
 *
 * <p>路径：{@code POST /api/plugin/billing/paypal/webhook}
 *
 * <p>关键设计：
 * <ul>
 *   <li>无需 JWT — {@link com.tang.plugin.config.JwtAuthFilter} 已将此路径加入 PUBLIC_EXACT_PATHS，
 *       安全由 PayPal webhook 签名校验保证</li>
 *   <li>签名校验调 PayPal 官方 {@code /v1/notifications/verify-webhook-signature} 接口</li>
 *   <li>响应策略：
 *     <ul>
 *       <li>200 OK：已收到并处理（或可安全忽略）— PayPal 不会重试</li>
 *       <li>5xx：签名校验 API 暂时不可用 — PayPal 会按指数退避重试（最多 25 次 / 3 天）</li>
 *     </ul>
 *   </li>
 *   <li>响应必须 < 30s（PayPal 超时阈值），所以业务逻辑同步执行</li>
 * </ul>
 *
 * <p>配置：在 Render Dashboard 设置 {@code TANG_PLUGIN_PAYPAL_WEBHOOK_ID}，
 * 在 PayPal Developer 后台将 webhook URL 指向 {@code https://shop-x2mw.onrender.com/api/plugin/billing/paypal/webhook}，
 * 订阅事件 {@code PAYMENT.CAPTURE.COMPLETED}（如需退款处理，再订阅 PAYMENT.CAPTURE.REFUNDED）。
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/billing/paypal")
public class PayPalWebhookController {

    @Resource
    private PayPalWebhookService payPalWebhookService;

    @Resource
    private BillingService billingService;

    /**
     * 接收 PayPal webhook 通知。
     *
     * <p>响应体（仅用于日志/调试，PayPal 不解析）：
     * <pre>
     * { "status": "OK" | "IGNORED" | "ERROR", "outcome": "...", "ref": "..." }
     * </pre>
     */
    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @RequestHeader HttpHeaders headers,
            @RequestBody String rawBody) {

        // 1) 签名校验（瞬时错误会抛 RuntimeException → 触发 5xx → PayPal 重试）
        boolean verified;
        try {
            verified = payPalWebhookService.verifySignature(headers, rawBody);
        } catch (RuntimeException e) {
            log.error("Webhook signature verification transient error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        if (!verified) {
            // 签名校验失败：返回 200 防止 PayPal 重试伪造请求造成 DoS
            // 真实 PayPal webhook 不会签名失败；伪造请求不应当让 PayPal 浪费重试额度
            log.warn("Webhook signature verification failed; acknowledging without processing");
            return ResponseEntity.ok(body("IGNORED", "SIGNATURE_FAILED", null));
        }

        // 2) 解析事件
        PayPalWebhookService.WebhookEvent event = payPalWebhookService.parseEvent(rawBody);
        if (event.eventType() == null) {
            log.warn("Webhook event could not be parsed; acknowledging");
            return ResponseEntity.ok(body("IGNORED", "PARSE_FAILED", null));
        }

        log.info("Webhook received: eventId={} type={} captureId={} orderId={} amountUsdCents={}",
                event.eventId(), event.eventType(), event.captureId(),
                event.paypalOrderId(), event.amountUsdCents());

        // 3) 业务处理（始终返回 ack=true，让 PayPal 不重试；运营通过日志发现异常）
        try {
            BillingService.WebhookHandleResult result = billingService.handleWebhookCapture(event);
            String status = result.acknowledged() ? "OK" : "ERROR";
            return ResponseEntity.ok(body(status, result.outcome(), result.ref()));
        } catch (Exception e) {
            // 业务处理异常：返回 500 让 PayPal 重试（业务逻辑应当是幂等的，重试是安全的）
            log.error("Webhook business processing error: eventId={} type={}",
                    event.eventId(), event.eventType(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private Map<String, Object> body(String status, String outcome, String ref) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("outcome", outcome);
        if (ref != null) m.put("ref", ref);
        return m;
    }
}
