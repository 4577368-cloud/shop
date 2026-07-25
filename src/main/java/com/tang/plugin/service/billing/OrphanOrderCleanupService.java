package com.tang.plugin.service.billing;

import com.tang.plugin.config.PayPalProperties;
import com.tang.plugin.domain.entity.user.PaymentOrder;
import com.tang.plugin.repository.PaymentOrderRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 孤儿支付订单自愈任务（P3.4）。
 *
 * <p>问题背景：用户在 PayPal 弹窗完成支付后，前端调 {@code /billing/paypal/{id}/capture}
 * 时可能因网络瞬断、服务重启、落库异常等原因中断，导致本地 payment_orders 状态卡在：
 * <ul>
 *   <li>{@code capturing}：已发起 capture 但未确认结果（PayPal 实际可能已扣款成功）</li>
 *   <li>{@code created/approved}：用户关闭弹窗未完成（PayPal 端订单仍在）</li>
 * </ul>
 *
 * <p>自愈策略：定时扫描超过 3 小时未推进的订单，向 PayPal 查询真实状态并补全本地状态：
 * <ul>
 *   <li>capturing + PayPal=COMPLETED → 调 {@link BillingService#healFromExternalCapture} 入账</li>
 *   <li>capturing + PayPal=APPROVED → 尝试重新 capture（PayPal 会幂等返回已 capture 结果）</li>
 *   <li>capturing + PayPal=VOIDED/NOT_FOUND → markFailed</li>
 *   <li>created/approved 超 24h → markFailed（用户已放弃）</li>
 * </ul>
 *
 * <p>调度：每小时一次，单次扫描上限 100 条（避免拖垮 PayPal API 配额）。
 * PayPal 未配置时直接跳过。
 */
@Slf4j
@Service
public class OrphanOrderCleanupService {

    /** capturing 状态超过此时长视为孤儿（3 小时）。 */
    private static final long CAPTURING_STALE_SECONDS = 3 * 3600L;

    /** created/approved 状态超过此时长视为用户已放弃（24 小时）。 */
    private static final long PENDING_STALE_SECONDS = 24 * 3600L;

    /** 单次扫描上限。 */
    private static final int BATCH_LIMIT = 100;

    @Resource
    private PaymentOrderRepository paymentOrderRepository;

    @Resource
    private PayPalClient payPalClient;

    @Resource
    private BillingService billingService;

    @Resource
    private PayPalProperties payPalProperties;

    /**
     * 每小时扫描一次孤儿订单。
     * fixedDelay：上次执行结束后等 1 小时再开始下一次（避免任务堆积）。
     * initialDelay：启动后延迟 5 分钟再首次执行（避免与应用启动冲突）。
     */
    @Scheduled(fixedDelayString = "${tang.plugin.billing.cleanup-interval-ms:3600000}",
               initialDelayString = "${tang.plugin.billing.cleanup-initial-delay-ms:300000}")
    public void cleanupStaleOrders() {
        if (!payPalProperties.isEnabled()) {
            // PayPal 未配置时跳过（开发/测试环境常见）
            return;
        }

        Instant now = Instant.now();
        Instant capturingCutoff = now.minusSeconds(CAPTURING_STALE_SECONDS);
        Instant pendingCutoff = now.minusSeconds(PENDING_STALE_SECONDS);

        int capturingHealed = 0;
        int capturingFailed = 0;
        int pendingFailed = 0;

        // 1) 处理 capturing 孤儿（3h 未完成）
        List<PaymentOrder> staleCapturing = paymentOrderRepository.listStaleCapturingOrders(capturingCutoff, BATCH_LIMIT);
        for (PaymentOrder order : staleCapturing) {
            try {
                String outcome = healCapturingOrder(order);
                if ("HEALED".equals(outcome)) {
                    capturingHealed++;
                } else if ("FAILED".equals(outcome)) {
                    capturingFailed++;
                }
                // RECHECK_LATER / UNKNOWN 不计入，等下次扫描
            } catch (Exception e) {
                log.error("Cleanup capturing order failed: paypalOrderId={}",
                        order.getPaypalOrderId(), e);
            }
        }

        // 2) 处理 created/approved 孤儿（24h 未推进）→ markFailed
        List<PaymentOrder> stalePending = paymentOrderRepository.listStalePendingOrders(pendingCutoff, BATCH_LIMIT);
        for (PaymentOrder order : stalePending) {
            try {
                int affected = paymentOrderRepository.markFailed(
                        order.getPaypalOrderId(), "Auto-expired (no capture within 24h)");
                if (affected > 0) {
                    pendingFailed++;
                    log.info("Auto-expired stale pending order: paypalOrderId={} status={} age={}h",
                            order.getPaypalOrderId(), order.getStatus(),
                            (now.getEpochSecond() - order.getUpdatedAt().getEpochSecond()) / 3600);
                }
            } catch (Exception e) {
                log.error("Mark pending order failed failed: paypalOrderId={}",
                        order.getPaypalOrderId(), e);
            }
        }

        if (!staleCapturing.isEmpty() || !stalePending.isEmpty()) {
            log.info("Orphan order cleanup done: capturing scanned={} healed={} failed={} | pending expired={}",
                    staleCapturing.size(), capturingHealed, capturingFailed, pendingFailed);
        }
    }

    /**
     * 自愈单个 capturing 订单。
     *
     * @return HEALED / FAILED / RECHECK_LATER / UNKNOWN
     */
    private String healCapturingOrder(PaymentOrder order) {
        String paypalOrderId = order.getPaypalOrderId();

        PayPalClient.OrderStatusResult status;
        try {
            status = payPalClient.getOrderStatus(paypalOrderId);
        } catch (Exception e) {
            log.warn("Query PayPal status failed (will recheck later): paypalOrderId={}", paypalOrderId, e);
            return "RECHECK_LATER";
        }

        String paypalStatus = status.paypalStatus();
        String captureId = status.captureId();

        // COMPLETED：PayPal 已扣款，自愈入账
        if ("COMPLETED".equals(paypalStatus)) {
            BillingService.WebhookHandleResult result = billingService.healFromExternalCapture(order, captureId);
            log.info("Capturing order healed from PayPal COMPLETED: paypalOrderId={} outcome={}",
                    paypalOrderId, result.outcome());
            return "HEALED";
        }

        // APPROVED：用户已批准但未 capture，尝试重新 capture
        if ("APPROVED".equals(paypalStatus)) {
            try {
                PayPalClient.CaptureResult capture = payPalClient.captureOrder(paypalOrderId);
                BillingService.WebhookHandleResult result = billingService.healFromExternalCapture(
                        order, capture.captureId());
                log.info("Capturing order re-captured: paypalOrderId={} outcome={}",
                        paypalOrderId, result.outcome());
                return "HEALED";
            } catch (Exception e) {
                log.warn("Re-capture failed (will recheck later): paypalOrderId={}", paypalOrderId, e);
                return "RECHECK_LATER";
            }
        }

        // VOIDED / NOT_FOUND：订单已失效或不存在，标记 failed
        if ("VOIDED".equals(paypalStatus) || "NOT_FOUND".equals(paypalStatus)) {
            paymentOrderRepository.markFailed(paypalOrderId,
                    "PayPal status: " + paypalStatus);
            log.info("Capturing order marked failed: paypalOrderId={} paypalStatus={}",
                    paypalOrderId, paypalStatus);
            return "FAILED";
        }

        // CREATED / SAVED / PAYER_ACTION_REQUIRED / UNKNOWN：未到终态，等下次扫描
        log.info("Capturing order not in terminal state yet: paypalOrderId={} paypalStatus={}",
                paypalOrderId, paypalStatus);
        return "UNKNOWN";
    }
}
