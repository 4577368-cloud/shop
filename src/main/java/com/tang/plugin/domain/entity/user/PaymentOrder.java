package com.tang.plugin.domain.entity.user;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * PayPal 支付订单（统一记录订单支付与余额充值）。
 * Table: payment_orders
 */
@Data
@Accessors(chain = true)
public class PaymentOrder {
    private Long id;
    private Long userId;
    /** PayPal 订单 ID（幂等键，全局唯一）。 */
    private String paypalOrderId;
    /** 用途：order_payment（订单支付）/ balance_recharge（余额充值）。 */
    private String purpose;
    /** order_payment 时为 shopify_order_id；balance_recharge 时为 null。 */
    private String refId;
    /** PayPal 计价金额（USD 分）。 */
    private Long amountUsdCents;
    /** balance_recharge 时记录入账 CNY（分）；order_payment 时为 null。 */
    private Long amountCnyCents;
    /** 状态：created / approved / captured / failed。 */
    private String status;
    /** capture 成功后 PayPal 返回的 capture ID。 */
    private String paypalCaptureId;
    /** 失败原因。 */
    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant capturedAt;
}
