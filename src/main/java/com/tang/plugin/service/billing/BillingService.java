package com.tang.plugin.service.billing;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.PayPalProperties;
import com.tang.plugin.domain.entity.user.AccountTransaction;
import com.tang.plugin.domain.entity.user.PaymentOrder;
import com.tang.plugin.domain.entity.user.UserAccount;
import com.tang.plugin.dto.billing.BillingDtos;
import com.tang.plugin.dto.billing.BillingDtos.AccountOverview;
import com.tang.plugin.dto.billing.BillingDtos.CapturePayPalOrderResponse;
import com.tang.plugin.dto.billing.BillingDtos.ConsumeBalanceRequest;
import com.tang.plugin.dto.billing.BillingDtos.ConsumeResult;
import com.tang.plugin.dto.billing.BillingDtos.CreatePayPalOrderRequest;
import com.tang.plugin.dto.billing.BillingDtos.CreatePayPalOrderResponse;
import com.tang.plugin.dto.billing.BillingDtos.PaymentOrderItem;
import com.tang.plugin.dto.billing.BillingDtos.PaymentOrderListResponse;
import com.tang.plugin.dto.billing.BillingDtos.RechargeRequest;
import com.tang.plugin.dto.billing.BillingDtos.TransactionItem;
import com.tang.plugin.dto.billing.BillingDtos.TransactionListResponse;
import com.tang.plugin.repository.AccountTransactionRepository;
import com.tang.plugin.repository.PaymentOrderRepository;
import com.tang.plugin.repository.UserAccountRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 账户余额业务逻辑：查询 / 消费 / 充值 / 流水查询。
 *
 * <p>并发安全策略：
 * <ul>
 *   <li>{@link #consumeBalance} 使用原子 UPDATE 扣减余额，避免悲观锁。
 *       若扣减成功但插入流水失败（极少），会出现余额对而不对账——可通过对账任务发现。</li>
 *   <li>{@link #recharge} 使用 TransactionTemplate 包裹「调整余额 + 写流水」两步。</li>
 * </ul>
 */
@Slf4j
@Service
public class BillingService {

    @Resource
    private UserAccountRepository accountRepository;

    @Resource
    private AccountTransactionRepository txnRepository;

    @Resource
    private PaymentOrderRepository paymentOrderRepository;

    @Resource
    private PayPalClient payPalClient;

    @Resource
    private PayPalProperties payPalProperties;

    @Resource
    private TransactionTemplate transactionTemplate;

    // ===== Overview =====

    /**
     * 获取账户概览。若账户不存在会懒创建（首次访问）。
     */
    public AccountOverview getOverview(Long userId) {
        UserAccount acc = ensureAccount(userId);
        return new AccountOverview(
                acc.getUserId(),
                acc.getBalanceCny(),
                acc.getTotalRecharged(),
                acc.getTotalConsumed(),
                acc.getTotalRefunded()
        );
    }

    /** 仅查余额，不懒创建（供余额检查使用，避免意外建空账户）。 */
    public Long getBalanceCny(Long userId) {
        return accountRepository.findByUserId(userId)
                .map(UserAccount::getBalanceCny)
                .orElse(0L);
    }

    // ===== Consume =====

    /**
     * 余额支付订单。原子扣减 + 写流水。
     *
     * <p>幂等性：当前实现不保证幂等。调用方需自行去重（如订单中心在订单状态变更前再次确认）。
     * 同一 shopifyOrderId 多次调用会每次都扣减。P3.2 将引入 idempotency_key 解决。
     *
     * @return ConsumeResult.success=true 表示扣减成功；false 表示余额不足或参数错误。
     */
    public ConsumeResult consumeBalance(Long userId, ConsumeBalanceRequest req) {
        if (req == null) {
            throw new CustomException("Request body is required", 400, "INVALID_REQUEST");
        }
        if (StringUtils.isBlank(req.shopifyOrderId())) {
            throw new CustomException("shopifyOrderId is required", 400, "SHOPIFY_ORDER_REQUIRED");
        }
        if (req.amountCny() == null || req.amountCny() <= 0) {
            throw new CustomException("amountCny must be positive (in cents)", 400, "INVALID_AMOUNT");
        }

        // 确保账户存在（避免因账户未创建而 tryConsume 影响 0 行被误判为余额不足）
        UserAccount acc = ensureAccount(userId);

        // 原子扣减：UPDATE ... WHERE balance >= ?。返回影响行数 1=成功，0=余额不足。
        int affected = accountRepository.tryConsume(userId, req.amountCny());
        if (affected == 0) {
            log.info("Consume rejected (insufficient balance): userId={} need={} have={}",
                    userId, req.amountCny(), acc.getBalanceCny());
            return new ConsumeResult(false, acc.getBalanceCny(), null, "INSUFFICIENT_BALANCE");
        }

        // 扣减成功后查询新余额（balance_after），写入流水。
        // 注意：在并发场景下，此处的 balance_after 可能与本次扣减后的余额不一致
        // （其他并发扣减已经又改了 balance）。但流水的 balance_after 仍是"扣减后的瞬时值"，
        // 对账时按流水顺序累加即可还原。
        Long balanceAfter = accountRepository.findByUserId(userId)
                .map(UserAccount::getBalanceCny)
                .orElseThrow(() -> new IllegalStateException("Account disappeared after consume"));

        String remark = buildConsumeRemark(req);

        AccountTransaction txn = new AccountTransaction()
                .setUserId(userId)
                .setType("consume")
                .setAmountCny(-req.amountCny())  // 出账为负
                .setBalanceBefore(balanceAfter + req.amountCny())  // 推算扣减前余额
                .setBalanceAfter(balanceAfter)
                .setRefType("order")
                .setRefId(req.shopifyOrderId())
                .setRemark(remark);
        txnRepository.insert(txn);

        log.info("Consume success: userId={} amount={}cny shopifyOrder={} txnId={} balanceAfter={}",
                userId, req.amountCny(), req.shopifyOrderId(), txn.getId(), balanceAfter);

        return new ConsumeResult(true, balanceAfter, String.valueOf(txn.getId()), null);
    }

    // ===== Recharge (P3.1 测试用) =====

    /**
     * 人工充值（仅用于 P3.1 测试）。P3.2 接入支付网关后将替换为创建充值订单流程。
     * 使用事务包裹「调整余额 + 写流水」。
     */
    public AccountOverview recharge(Long userId, RechargeRequest req) {
        if (req == null || req.amountCny() == null || req.amountCny() <= 0) {
            throw new CustomException("amountCny must be positive (in cents)", 400, "INVALID_AMOUNT");
        }
        if (req.amountCny() > 1_000_000_00L) {  // 单次上限 100 万 CNY
            throw new CustomException("amountCny exceeds single-recharge limit", 400, "AMOUNT_TOO_LARGE");
        }

        ensureAccount(userId);

        return transactionTemplate.execute(status -> {
            // 调整前余额
            Long balanceBefore = accountRepository.findByUserId(userId)
                    .map(UserAccount::getBalanceCny)
                    .orElse(0L);

            // 调整余额（加余额，累加 total_recharged）
            accountRepository.adjustBalance(userId, req.amountCny(), req.amountCny(), 0L);

            Long balanceAfter = balanceBefore + req.amountCny();

            AccountTransaction txn = new AccountTransaction()
                    .setUserId(userId)
                    .setType("recharge")
                    .setAmountCny(req.amountCny())  // 入账为正
                    .setBalanceBefore(balanceBefore)
                    .setBalanceAfter(balanceAfter)
                    .setRefType("manual")
                    .setRefId(null)
                    .setRemark(StringUtils.isBlank(req.remark()) ? "Manual recharge" : req.remark());
            txnRepository.insert(txn);

            log.info("Recharge success: userId={} amount={}cny txnId={} balanceAfter={}",
                    userId, req.amountCny(), txn.getId(), balanceAfter);

            // 返回最新账户
            return accountRepository.findByUserId(userId)
                    .map(a -> new AccountOverview(
                            a.getUserId(), a.getBalanceCny(), a.getTotalRecharged(),
                            a.getTotalConsumed(), a.getTotalRefunded()))
                    .orElseThrow(() -> new IllegalStateException("Account disappeared after recharge"));
        });
    }

    // ===== Transactions =====

    public TransactionListResponse listTransactions(Long userId, String type, int limit, int offset) {
        int total = txnRepository.countByUser(userId, type);
        List<AccountTransaction> rows = txnRepository.listByUser(userId, type, limit, offset);
        List<TransactionItem> items = rows.stream()
                .map(this::toItem)
                .collect(Collectors.toList());
        return new TransactionListResponse(items, total, limit, offset);
    }

    // ===== Payment Orders (P3.5) =====

    /**
     * 查询当前用户的支付订单列表（分页）。
     * 支持按 status 过滤（created/approved/capturing/captured/failed）。
     */
    public PaymentOrderListResponse listPaymentOrders(Long userId, String status, int limit, int offset) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(offset, 0);

        List<PaymentOrder> rows = paymentOrderRepository.listByUserAndStatus(userId, status, safeLimit, safeOffset);
        int total = paymentOrderRepository.countByUserAndStatus(userId, status);
        List<PaymentOrderItem> items = rows.stream()
                .map(this::toPaymentOrderItem)
                .collect(Collectors.toList());
        return new PaymentOrderListResponse(items, total, safeLimit, safeOffset);
    }

    /** 查询单个支付订单详情（按 id + userId，防止越权）。 */
    public PaymentOrderItem getPaymentOrder(Long userId, Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw new CustomException("orderId is required", 400, "INVALID_ORDER_ID");
        }
        PaymentOrder order = paymentOrderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new CustomException("Payment order not found", 404, "ORDER_NOT_FOUND"));
        return toPaymentOrderItem(order);
    }

    private PaymentOrderItem toPaymentOrderItem(PaymentOrder o) {
        return new PaymentOrderItem(
                o.getId(),
                o.getPaypalOrderId(),
                o.getPurpose(),
                o.getRefId(),
                o.getAmountUsdCents(),
                o.getAmountCnyCents(),
                o.getStatus(),
                o.getPaypalCaptureId(),
                o.getFailureReason(),
                o.getCreatedAt(),
                o.getUpdatedAt(),
                o.getCapturedAt()
        );
    }

    // ===== Helpers =====

    private UserAccount ensureAccount(Long userId) {
        return accountRepository.insertIfAbsent(userId);
    }

    private TransactionItem toItem(AccountTransaction t) {
        return new TransactionItem(
                t.getId(),
                t.getType(),
                t.getAmountCny(),
                t.getBalanceBefore(),
                t.getBalanceAfter(),
                t.getRefType(),
                t.getRefId(),
                t.getRemark(),
                t.getCreatedAt()
        );
    }

    private String buildConsumeRemark(ConsumeBalanceRequest req) {
        StringBuilder sb = new StringBuilder("Order payment");
        if (req.amountUsd() != null) {
            sb.append(" (").append(formatUsd(req.amountUsd())).append(")");
        }
        if (StringUtils.isNotBlank(req.remark())) {
            sb.append(" · ").append(req.remark());
        }
        return sb.toString();
    }

    /** amountUsd 以分为单位传入（避免浮点），格式化为 $X.YY。 */
    private String formatUsd(Long amountUsdCents) {
        if (amountUsdCents == null) return "$0.00";
        long dollars = amountUsdCents / 100;
        long cents = Math.abs(amountUsdCents % 100);
        return String.format("$%d.%02d", dollars, cents);
    }

    // ===== PayPal (P3.2) =====

    /**
     * 创建 PayPal 订单。
     * <ul>
     *   <li>order_payment：用于支付 Shopify 订单（不增加余额，捕获成功后由前端标记订单为 paid）</li>
     *   <li>balance_recharge：用于充值余额（捕获成功后由后端按汇率换算 CNY 入账）</li>
     * </ul>
     */
    public CreatePayPalOrderResponse createPayPalOrder(Long userId, CreatePayPalOrderRequest req) {
        if (!payPalProperties.isEnabled()) {
            throw new CustomException("PayPal is not configured", 503, "PAYPAL_NOT_CONFIGURED");
        }
        if (req == null) {
            throw new CustomException("Request body is required", 400, "INVALID_REQUEST");
        }
        String purpose = req.purpose();
        if (!"order_payment".equals(purpose) && !"balance_recharge".equals(purpose)) {
            throw new CustomException("Invalid purpose: must be order_payment or balance_recharge",
                    400, "INVALID_PURPOSE");
        }
        if (req.amountUsdCents() == null || req.amountUsdCents() <= 0) {
            throw new CustomException("amountUsdCents must be positive", 400, "INVALID_AMOUNT");
        }
        // 单笔上限：$10000（修复 M6）。前端 UI max=10000 但 UI 易绕过。
        if (req.amountUsdCents() > 1_000_000L) {  // 1M 美分 = $10000
            throw new CustomException("amountUsdCents exceeds single-payment limit ($10000)",
                    400, "AMOUNT_TOO_LARGE");
        }
        if ("order_payment".equals(purpose) && StringUtils.isBlank(req.refId())) {
            throw new CustomException("refId (shopifyOrderId) is required for order_payment",
                    400, "SHOPIFY_ORDER_REQUIRED");
        }

        // 修复 C4：创建订单前确保账户存在（balance_recharge 场景必需）
        if ("balance_recharge".equals(purpose)) {
            ensureAccount(userId);
        }

        // 调 PayPal API 创建订单
        String description = StringUtils.isNotBlank(req.description())
                ? req.description()
                : ("order_payment".equals(purpose) ? "Order payment" : "Balance recharge");
        String customId = "user_" + userId;  // PayPal custom_id 字段，便于 webhook 关联

        String paypalOrderId = payPalClient.createOrder(req.amountUsdCents(), description, customId);

        // 预估 balance_recharge 入账 CNY（捕获时再实际换算）
        Long estimatedCnyCents = "balance_recharge".equals(purpose)
                ? Math.round(req.amountUsdCents() * payPalProperties.getUsdToCnyRate())
                : null;

        // 落库
        PaymentOrder order = new PaymentOrder()
                .setUserId(userId)
                .setPaypalOrderId(paypalOrderId)
                .setPurpose(purpose)
                .setRefId("order_payment".equals(purpose) ? req.refId() : null)
                .setAmountUsdCents(req.amountUsdCents())
                .setAmountCnyCents(estimatedCnyCents)
                .setStatus("created");
        paymentOrderRepository.insert(order);

        log.info("PayPal order created in DB: id={} paypalOrderId={} purpose={} amountUsd={}cny",
                order.getId(), paypalOrderId, purpose, req.amountUsdCents());

        return new CreatePayPalOrderResponse(
                paypalOrderId,
                purpose,
                req.amountUsdCents(),
                estimatedCnyCents,
                "created"
        );
    }

    /**
     * 捕获 PayPal 订单。用户在 PayPal 弹窗完成批准后，前端调用此接口。
     *
     * <p>关键设计（修复 C1/C2/C3）：
     * <ul>
     *   <li>乐观锁：入口 tryStartCapture 把 status 从 created/approved 推进到 capturing，
     *       并发请求返回 0 行 → 直接走幂等查询路径，避免重复 capture。</li>
     *   <li>状态机：capturing 状态只允许前进到 captured，不允许回退到 failed
     *       （除非 PayPal API 明确返回 capture 失败）。这避免了"PayPal 已收钱但本地标 failed"。</li>
     *   <li>post-capture 落库失败时：保留 capturing 状态，回滚事务但不 markFailed。
     *       下次重试会再次走 tryStartCapture（已 capturing 则返回 0，走幂等）。
     *       P3.3 webhook 会查询 PayPal 真实状态并补写余额（自愈）。</li>
     * </ul>
     */
    public CapturePayPalOrderResponse capturePayPalOrder(Long userId, String paypalOrderId) {
        if (!payPalProperties.isEnabled()) {
            throw new CustomException("PayPal is not configured", 503, "PAYPAL_NOT_CONFIGURED");
        }
        if (StringUtils.isBlank(paypalOrderId)) {
            throw new CustomException("paypalOrderId is required", 400, "INVALID_REQUEST");
        }

        PaymentOrder order = paymentOrderRepository.findByPaypalOrderId(paypalOrderId)
                .orElseThrow(() -> new CustomException("PayPal order not found: " + paypalOrderId,
                        404, "PAYPAL_ORDER_NOT_FOUND"));

        // 安全检查：只能捕获自己的订单
        if (!order.getUserId().equals(userId)) {
            throw new CustomException("PayPal order does not belong to current user",
                    403, "PAYPAL_ORDER_OWNERSHIP");
        }

        // 幂等：已 captured 直接返回成功
        if ("captured".equals(order.getStatus())) {
            log.info("PayPal order already captured, returning idempotent success: {}",
                    paypalOrderId);
            Long balanceAfter = "balance_recharge".equals(order.getPurpose())
                    ? getBalanceCny(userId)
                    : null;
            return new CapturePayPalOrderResponse(true, "captured", order.getPurpose(),
                    order.getRefId(), balanceAfter, null);
        }

        // 幂等：capturing 状态说明有其他请求正在 capture（或上次 capture 后落库失败）
        // 直接返回 "进行中"，前端可轮询或提示用户稍后查看余额
        if ("capturing".equals(order.getStatus())) {
            log.info("PayPal order is being captured by another request: {}", paypalOrderId);
            return new CapturePayPalOrderResponse(false, "capturing", order.getPurpose(),
                    order.getRefId(), null, "CAPTURE_IN_PROGRESS");
        }

        // 乐观锁：原子推进 created/approved → capturing
        int locked = paymentOrderRepository.tryStartCapture(paypalOrderId);
        if (locked == 0) {
            // 并发竞态失败：重新查一次状态
            PaymentOrder fresh = paymentOrderRepository.findByPaypalOrderId(paypalOrderId).orElse(order);
            if ("captured".equals(fresh.getStatus())) {
                Long balanceAfter = "balance_recharge".equals(fresh.getPurpose())
                        ? getBalanceCny(userId)
                        : null;
                return new CapturePayPalOrderResponse(true, "captured", fresh.getPurpose(),
                        fresh.getRefId(), balanceAfter, null);
            }
            return new CapturePayPalOrderResponse(false, "capturing", fresh.getPurpose(),
                    fresh.getRefId(), null, "CAPTURE_IN_PROGRESS");
        }

        try {
            // 调 PayPal API 捕获资金
            PayPalClient.CaptureResult capture = payPalClient.captureOrder(paypalOrderId);

            // capture 成功：根据用途分别处理（事务内落库）
            if ("balance_recharge".equals(order.getPurpose())) {
                return handleRechargeCapture(userId, order, capture);
            } else {
                return handleOrderPaymentCapture(userId, order, capture);
            }
        } catch (CustomException e) {
            // capture 真失败（PayPal 拒绝）：回退 capturing → approved，标记 failed
            paymentOrderRepository.revertCapturingToApproved(paypalOrderId);
            paymentOrderRepository.markFailed(paypalOrderId, e.getMessage());
            log.warn("PayPal capture failed: orderId={} reason={}", paypalOrderId, e.getMessage());
            return new CapturePayPalOrderResponse(false, "failed", order.getPurpose(),
                    order.getRefId(), null, e.getCode());
        } catch (Exception e) {
            // 未知异常（可能是网络瞬断，PayPal 实际状态未知）：保留 capturing 状态，
            // 等 P3.3 webhook 自愈或用户重试。不 markFailed。
            log.error("PayPal capture unexpected error (keeping capturing status): orderId={}",
                    paypalOrderId, e);
            return new CapturePayPalOrderResponse(false, "capturing", order.getPurpose(),
                    order.getRefId(), null, "CAPTURE_UNKNOWN_RETRY_LATER");
        }
    }

    /**
     * 余额充值捕获：按汇率换算 CNY 入账（事务包裹「ensureAccount + 调整余额 + 写流水 + 标记订单」）。
     *
     * <p>修复 C4：必须 ensureAccount，否则 adjustBalance 命中 0 行被静默忽略。
     * <p>修复 C1：若事务内任何一步抛异常，整体回滚；markCaptured 也会回滚，
     *   保留 capturing 状态，等重试或 webhook 自愈。
     */
    private CapturePayPalOrderResponse handleRechargeCapture(Long userId, PaymentOrder order,
                                                              PayPalClient.CaptureResult capture) {
        // 实际入账 CNY（用配置的汇率换算）
        long cnyCents = Math.round(order.getAmountUsdCents() * payPalProperties.getUsdToCnyRate());

        return transactionTemplate.execute(status -> {
            // 0) ensureAccount（修复 C4）：保证账户行存在
            UserAccount acc = ensureAccount(userId);

            // 1) 读 balance_before（修复 M5：先读再调，语义直白）
            Long balanceBefore = acc.getBalanceCny();

            // 2) 调整余额（加余额，累加 total_recharged）
            int adjusted = accountRepository.adjustBalance(userId, cnyCents, cnyCents, 0L);
            if (adjusted == 0) {
                // 极少发生（ensureAccount 已建账户），但作为兜底
                throw new IllegalStateException("adjustBalance affected 0 rows for userId=" + userId);
            }

            Long balanceAfter = balanceBefore + cnyCents;

            // 3) 写流水
            AccountTransaction txn = new AccountTransaction()
                    .setUserId(userId)
                    .setType("recharge")
                    .setAmountCny(cnyCents)
                    .setBalanceBefore(balanceBefore)
                    .setBalanceAfter(balanceAfter)
                    .setRefType("paypal")
                    .setRefId(order.getPaypalOrderId())
                    .setRemark("PayPal recharge " + formatUsd(order.getAmountUsdCents()));
            txnRepository.insert(txn);

            // 4) 标记订单为 captured
            int marked = paymentOrderRepository.markCaptured(order.getPaypalOrderId(),
                    capture.captureId(), cnyCents);
            if (marked == 0) {
                // 状态被其他事务改了（不应发生，capturing 已被本流程独占）
                throw new IllegalStateException("markCaptured affected 0 rows for " + order.getPaypalOrderId());
            }

            log.info("PayPal recharge captured: userId={} paypalOrderId={} cny={}cny balanceAfter={}",
                    userId, order.getPaypalOrderId(), cnyCents, balanceAfter);

            return new CapturePayPalOrderResponse(true, "captured", order.getPurpose(),
                    order.getRefId(), balanceAfter, null);
        });
    }

    /**
     * 订单支付捕获：不增加余额（资金已通过 PayPal 直收），仅标记订单为 captured。
     * 前端据 success 把 Shopify 订单状态推进到 preparing。
     */
    private CapturePayPalOrderResponse handleOrderPaymentCapture(Long userId, PaymentOrder order,
                                                                  PayPalClient.CaptureResult capture) {
        paymentOrderRepository.markCaptured(order.getPaypalOrderId(),
                capture.captureId(), null);

        log.info("PayPal order payment captured: userId={} paypalOrderId={} shopifyOrder={} usd={}cny",
                userId, order.getPaypalOrderId(), order.getRefId(), order.getAmountUsdCents());

        return new CapturePayPalOrderResponse(true, "captured", order.getPurpose(),
                order.getRefId(), null, null);
    }

    // ===== Webhook (P3.3) =====

    /**
     * 处理 PayPal webhook 的 PAYMENT.CAPTURE.COMPLETED 事件。
     *
     * <p>核心场景：用户在弹窗完成支付后，前端调 {@link #capturePayPalOrder} 失败
     * （网络瞬断/服务重启/落库异常），但 PayPal 实际已扣款成功。此时 webhook 是兜底入账通道。
     *
     * <p>幂等策略：
     * <ul>
     *   <li>已 captured：直接返回 no-op（避免重复入账）</li>
     *   <li>已 failed：不覆盖（人工对账）</li>
     *   <li>created/approved/capturing：补全入账流程（自愈）</li>
     * </ul>
     *
     * @return 处理结果（用于日志和 controller 响应，但 controller 始终返回 200 OK 防止 PayPal 重试）
     */
    public WebhookHandleResult handleWebhookCapture(PayPalWebhookService.WebhookEvent event) {
        if (event == null || event.eventType() == null) {
            return new WebhookHandleResult(false, "INVALID_EVENT", null);
        }

        // 只处理 CAPTURE.COMPLETED；DENIED/REFUNDED 暂不处理
        if (!"PAYMENT.CAPTURE.COMPLETED".equals(event.eventType())) {
            log.info("Webhook event type {} not handled (only PAYMENT.CAPTURE.COMPLETED)", event.eventType());
            return new WebhookHandleResult(true, "IGNORED", event.eventType());
        }

        // 1) 定位订单：优先 paypalOrderId，回退 captureId
        PaymentOrder order = null;
        if (event.paypalOrderId() != null) {
            order = paymentOrderRepository.findByPaypalOrderId(event.paypalOrderId()).orElse(null);
        }
        if (order == null && event.captureId() != null) {
            order = paymentOrderRepository.findByCaptureId(event.captureId()).orElse(null);
        }
        if (order == null) {
            log.warn("Webhook references unknown order: paypalOrderId={} captureId={}",
                    event.paypalOrderId(), event.captureId());
            // 仍返回 OK 防止 PayPal 无限重试；运营通过日志发现并人工补单
            return new WebhookHandleResult(true, "ORDER_NOT_FOUND", event.eventType());
        }

        // 2) 幂等：已 captured 直接返回
        if ("captured".equals(order.getStatus())) {
            log.info("Webhook for already-captured order: {} (no-op)", order.getPaypalOrderId());
            return new WebhookHandleResult(true, "ALREADY_CAPTURED", order.getPaypalOrderId());
        }

        // 3) failed 状态不覆盖，等人工对账
        if ("failed".equals(order.getStatus())) {
            log.warn("Webhook for failed order: {} (manual reconcile needed)", order.getPaypalOrderId());
            return new WebhookHandleResult(true, "ORDER_FAILED_SKIP", order.getPaypalOrderId());
        }

        // 4) 自愈：created/approved/capturing → captured
        //    （capturing 是 capturePayPalOrder 中断后的状态，webhook 是兜底）
        log.info("Webhook self-healing capture: paypalOrderId={} currentStatus={}",
                order.getPaypalOrderId(), order.getStatus());

        String captureId = event.captureId() != null ? event.captureId() : order.getPaypalCaptureId();

        if ("balance_recharge".equals(order.getPurpose())) {
            return healRechargeCapture(order, captureId);
        } else {
            return healOrderPaymentCapture(order, captureId);
        }
    }

    /**
     * 自愈入账：已知 PayPal 端 capture 已成功，但本地状态卡在 capturing。
     * 供 {@link com.tang.plugin.service.billing.OrphanOrderCleanupService} 调用。
     *
     * <p>复用 webhook 自愈逻辑：按 purpose 分别走余额入账或仅 markCaptured。
     */
    public WebhookHandleResult healFromExternalCapture(PaymentOrder order, String captureId) {
        if ("balance_recharge".equals(order.getPurpose())) {
            return healRechargeCapture(order, captureId);
        } else {
            return healOrderPaymentCapture(order, captureId);
        }
    }

    /**
     * Webhook 自愈：余额充值入账。
     * 与 {@link #handleRechargeCapture} 同样的事务包裹逻辑，但不需要再调 PayPal API
     * （webhook 本身就是 PayPal 发出的成功通知）。
     */
    private WebhookHandleResult healRechargeCapture(PaymentOrder order, String captureId) {
        long cnyCents = Math.round(order.getAmountUsdCents() * payPalProperties.getUsdToCnyRate());
        Long userId = order.getUserId();

        transactionTemplate.executeWithoutResult(status -> {
            // 0) ensureAccount
            UserAccount acc = ensureAccount(userId);

            // 1) 读 balance_before
            Long balanceBefore = acc.getBalanceCny();

            // 2) 调整余额（加余额，累加 total_recharged）
            int adjusted = accountRepository.adjustBalance(userId, cnyCents, cnyCents, 0L);
            if (adjusted == 0) {
                throw new IllegalStateException("adjustBalance affected 0 rows for userId=" + userId);
            }

            Long balanceAfter = balanceBefore + cnyCents;

            // 3) 写流水
            AccountTransaction txn = new AccountTransaction()
                    .setUserId(userId)
                    .setType("recharge")
                    .setAmountCny(cnyCents)
                    .setBalanceBefore(balanceBefore)
                    .setBalanceAfter(balanceAfter)
                    .setRefType("paypal")
                    .setRefId(order.getPaypalOrderId())
                    .setRemark("PayPal recharge (webhook) " + formatUsd(order.getAmountUsdCents()));
            txnRepository.insert(txn);

            // 4) 标记订单为 captured
            int marked = paymentOrderRepository.markCaptured(order.getPaypalOrderId(), captureId, cnyCents);
            if (marked == 0) {
                throw new IllegalStateException("markCaptured affected 0 rows for " + order.getPaypalOrderId());
            }

            log.info("Webhook self-healed recharge: userId={} paypalOrderId={} cny={}cny balanceAfter={}",
                    userId, order.getPaypalOrderId(), cnyCents, balanceAfter);
        });

        return new WebhookHandleResult(true, "HEALED_RECHARGE", order.getPaypalOrderId());
    }

    /**
     * Webhook 自愈：订单支付（不入账余额，仅 markCaptured）。
     */
    private WebhookHandleResult healOrderPaymentCapture(PaymentOrder order, String captureId) {
        int marked = paymentOrderRepository.markCaptured(order.getPaypalOrderId(), captureId, null);
        if (marked == 0) {
            log.warn("Webhook markCaptured affected 0 rows: {} (concurrent state change?)",
                    order.getPaypalOrderId());
            return new WebhookHandleResult(true, "CONCURRENT_SKIP", order.getPaypalOrderId());
        }
        log.info("Webhook self-healed order payment: paypalOrderId={} shopifyOrder={}",
                order.getPaypalOrderId(), order.getRefId());
        return new WebhookHandleResult(true, "HEALED_ORDER_PAYMENT", order.getPaypalOrderId());
    }

    /** Webhook 处理结果。controller 始终返回 200，但 result 用于日志和监控。 */
    public record WebhookHandleResult(boolean acknowledged, String outcome, String ref) {}
}
