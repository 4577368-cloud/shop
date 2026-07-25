package com.tang.plugin.dto.billing;

/**
 * Billing (P3 account balance) request/response DTOs (Java records).
 */
public final class BillingDtos {

    private BillingDtos() {}

    // ===== Consume (余额支付订单) =====

    /**
     * 余额支付订单请求。由订单中心 payment-modal 调用。
     *
     * @param shopifyOrderId Shopify 订单号（用于幂等和审计）
     * @param amountCny      扣款金额（分）。必须 > 0
     * @param amountUsd      原始 USD 金额（保留两位小数 * 100 后传入；仅用于备注，不参与计算）
     * @param remark         可选备注
     */
    public record ConsumeBalanceRequest(
            String shopifyOrderId,
            Long amountCny,
            Long amountUsd,
            String remark
    ) {}

    /** 消费结果。 */
    public record ConsumeResult(
            boolean success,
            Long balanceAfter,
            String transactionId,
            String errorCode  // INSUFFICIENT_BALANCE / INVALID_AMOUNT / SHOPIFY_ORDER_REQUIRED
    ) {}

    // ===== Overview =====

    public record AccountOverview(
            Long userId,
            Long balanceCny,
            Long totalRecharged,
            Long totalConsumed,
            Long totalRefunded
    ) {}

    // ===== Transactions list =====

    public record TransactionItem(
            Long id,
            String type,
            Long amountCny,
            Long balanceBefore,
            Long balanceAfter,
            String refType,
            String refId,
            String remark,
            java.time.Instant createdAt
    ) {}

    public record TransactionListResponse(
            java.util.List<TransactionItem> items,
            int total,
            int limit,
            int offset
    ) {}

    // ===== Recharge (人工调整，P3.1 测试用；P3.2 接入支付网关后弃用) =====

    /**
     * 人工充值（仅用于 P3.1 测试）。生产环境需移除或限定管理员角色。
     *
     * @param amountCny 充值金额（分），必须 > 0
     * @param remark    备注
     */
    public record RechargeRequest(
            Long amountCny,
            String remark
    ) {}

    // ===== PayPal (P3.2) =====

    /**
     * 创建 PayPal 订单请求。
     *
     * @param purpose         用途：order_payment（订单支付）/ balance_recharge（余额充值）
     * @param refId           order_payment 时为 shopify_order_id；balance_recharge 时为 null
     * @param amountUsdCents  PayPal 计价金额（USD 分）
     * @param description     订单描述（显示在 PayPal 弹窗）
     */
    public record CreatePayPalOrderRequest(
            String purpose,
            String refId,
            Long amountUsdCents,
            String description
    ) {}

    /**
     * 创建 PayPal 订单响应。前端用 paypalOrderId 调 PayPal JS SDK。
     */
    public record CreatePayPalOrderResponse(
            String paypalOrderId,
            String purpose,
            Long amountUsdCents,
            Long amountCnyCents,    // balance_recharge 时返回预估入账 CNY（分）
            String status
    ) {}

    /**
     * 捕获 PayPal 订单响应。前端据 success 判断是否关闭弹窗。
     */
    public record CapturePayPalOrderResponse(
            boolean success,
            String status,
            String purpose,
            String refId,
            Long balanceAfter,      // balance_recharge 时返回新余额（分 CNY）；order_payment 时为 null
            String errorCode
    ) {}

    // ===== Credits (P4) =====

    /** 积分账户概览。 */
    public record CreditOverview(
            Long userId,
            Integer balanceCredits,
            Integer totalGranted,
            Integer totalConsumed,
            Integer totalExpired
    ) {}

    /**
     * 积分消耗请求。由运营中心调用。
     *
     * @param endpoint 调用的接口名（如 ad-products/search）
     * @param amount   消耗积分数（必须 > 0）
     * @param refType  关联业务类型（marketing_api / manual 等）
     * @param refId    关联业务 ID（可选）
     * @param remark   备注（可选）
     */
    public record ConsumeCreditsRequest(
            String endpoint,
            Integer amount,
            String refType,
            String refId,
            String remark
    ) {}

    /** 积分消耗结果。 */
    public record ConsumeCreditsResult(
            boolean success,
            Integer balanceAfter,
            Long transactionId,
            String errorCode  // INSUFFICIENT_CREDITS / INVALID_AMOUNT / ENDPOINT_REQUIRED
    ) {}

    /**
     * 测试用发放积分请求（P4 阶段无支付入口，用于测试运营中心扣减流程）。
     * P5 接入支付后将弃用。
     *
     * @param amount       发放积分数（必须 > 0）
     * @param sourceType   来源类型：subscription / credit_pack / promo / manual
     * @param sourceId     来源 ID（可选）
     * @param expiresAtStr 过期时间 ISO-8601 字符串（可选，null = 永不过期）
     * @param remark       备注（可选）
     */
    public record GrantCreditsRequest(
            Integer amount,
            String sourceType,
            Long sourceId,
            String expiresAtStr,
            String remark
    ) {}

    /** 发放积分结果。 */
    public record GrantCreditsResult(
            boolean success,
            Integer balanceAfter,
            Long lotId,
            Long transactionId
    ) {}

    public record CreditTransactionItem(
            Long id,
            String type,
            Integer amount,
            Integer balanceBefore,
            Integer balanceAfter,
            String refType,
            String refId,
            String endpoint,
            String remark,
            java.time.Instant createdAt
    ) {}

    public record CreditTransactionListResponse(
            java.util.List<CreditTransactionItem> items,
            int total,
            int limit,
            int offset
    ) {}

    public record CreditLotItem(
            Long id,
            String sourceType,
            Long sourceId,
            Integer amountGranted,
            Integer amountConsumed,
            Integer amountExpired,
            Integer remaining,
            java.time.Instant expiresAt,
            java.time.Instant createdAt
    ) {}

    public record CreditLotListResponse(
            java.util.List<CreditLotItem> items,
            int total,
            int limit,
            int offset
    ) {}

    // ===== Payment Orders (P3.5 — expose list/detail) =====

    /** 支付订单列表项。 */
    public record PaymentOrderItem(
            Long id,
            String paypalOrderId,
            String purpose,           // order_payment / balance_recharge
            String refId,             // order_payment 时为 shopify_order_id
            Long amountUsdCents,
            Long amountCnyCents,      // balance_recharge 时为入账 CNY；order_payment 时可能为 null
            String status,            // created / approved / capturing / captured / failed
            String paypalCaptureId,
            String failureReason,
            java.time.Instant createdAt,
            java.time.Instant updatedAt,
            java.time.Instant capturedAt
    ) {}

    public record PaymentOrderListResponse(
            java.util.List<PaymentOrderItem> items,
            int total,
            int limit,
            int offset
    ) {}
}

