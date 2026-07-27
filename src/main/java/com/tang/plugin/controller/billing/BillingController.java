package com.tang.plugin.controller.billing;

import com.tang.plugin.dto.billing.BillingDtos;
import com.tang.plugin.dto.billing.BillingDtos.AccountOverview;
import com.tang.plugin.dto.billing.BillingDtos.GrantSubscriptionRequest;
import com.tang.plugin.dto.billing.BillingDtos.GrantSubscriptionResult;
import com.tang.plugin.dto.billing.BillingDtos.CapturePayPalOrderResponse;
import com.tang.plugin.dto.billing.BillingDtos.ConsumeBalanceRequest;
import com.tang.plugin.dto.billing.BillingDtos.ConsumeResult;
import com.tang.plugin.dto.billing.BillingDtos.CreatePackOrderRequest;
import com.tang.plugin.dto.billing.BillingDtos.CreatePayPalOrderRequest;
import com.tang.plugin.dto.billing.BillingDtos.CreatePayPalOrderResponse;
import com.tang.plugin.dto.billing.BillingDtos.CreateSubscriptionRequest;
import com.tang.plugin.dto.billing.BillingDtos.PaymentOrderItem;
import com.tang.plugin.dto.billing.BillingDtos.PaymentOrderListResponse;
import com.tang.plugin.dto.billing.BillingDtos.RechargeRequest;
import com.tang.plugin.dto.billing.BillingDtos.TransactionListResponse;
import com.tang.plugin.service.billing.BillingService;
import com.tang.plugin.service.user.AdminGuard;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 账户余额 / 计费接口（P3.1）。
 *
 * <p>路径前缀 {@code /api/plugin/billing/**} 已在 {@code JwtAuthFilter} 中标记为受保护，
 * 所有请求必须携带有效的 {@code tb_access} cookie，过滤器会注入 {@code userId} 到 request attribute。
 *
 * <p>P3.1 范围：
 * <ul>
 *   <li>GET  /overview               — 账户概览（余额 + 累计充值/消费/退款）</li>
 *   <li>GET  /account/transactions   — 余额流水（分页 + 类型筛选）</li>
 *   <li>POST /consume/balance        — 余额支付订单（订单中心 payment-modal 调用）</li>
 *   <li>POST /recharge               — 人工充值（仅 P3.1 测试；P3.2 接入支付网关后弃用）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/billing")
public class BillingController {

    @Resource
    private BillingService billingService;
    @Resource
    private AdminGuard adminGuard;

    @GetMapping("/overview")
    public ResponseEntity<AccountOverview> overview(HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        return ResponseEntity.ok(billingService.getOverview(userId));
    }

    @GetMapping("/account/transactions")
    public ResponseEntity<TransactionListResponse> listTransactions(
            HttpServletRequest httpRequest,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        Long userId = currentUserId(httpRequest);
        return ResponseEntity.ok(billingService.listTransactions(userId, type, limit, offset));
    }

    /**
     * 余额支付订单。订单中心 payment-modal 在用户确认支付后调用。
     * 成功返回 200 + ConsumeResult；余额不足返回 200 + success=false（非错误，UI 展示提示即可）。
     */
    @PostMapping("/consume/balance")
    public ResponseEntity<ConsumeResult> consumeBalance(
            HttpServletRequest httpRequest,
            @RequestBody ConsumeBalanceRequest req) {
        Long userId = currentUserId(httpRequest);
        ConsumeResult result = billingService.consumeBalance(userId, req);
        return ResponseEntity.ok(result);
    }

    /**
     * 人工充值（仅 P3.1 测试用）。生产环境应通过支付网关回调触发，不暴露此接口。
     * 当前默认允许登录用户自充值以便于联调；P3.2 将加管理员校验或下线。
     */
    @PostMapping("/recharge")
    public ResponseEntity<AccountOverview> recharge(
            HttpServletRequest httpRequest,
            @RequestBody RechargeRequest req) {
        Long userId = currentUserId(httpRequest);
        adminGuard.assertAdmin(userId);
        return ResponseEntity.ok(billingService.recharge(userId, req));
    }

    /**
     * 测试用：为账号发放订阅（绕过 PayPal 流程）。admin 守卫。
     * 用于联调阶段解封日调用上限（匿名 5 / Starter 80 / Growth 200）。
     * targetUserId 省略时发放给当前登录用户；指定时仅管理员可为他人发放。
     * 生产接入支付后应下线，或保留为内部运维工具。
     */
    @PostMapping("/admin/grant-subscription")
    public ResponseEntity<GrantSubscriptionResult> grantSubscription(
            HttpServletRequest httpRequest,
            @RequestBody GrantSubscriptionRequest req) {
        Long caller = currentUserId(httpRequest);
        adminGuard.assertAdmin(caller);
        Long target = (req.targetUserId() != null) ? req.targetUserId() : caller;
        return ResponseEntity.ok(billingService.grantSubscription(target, req.planCode()));
    }

    // ===== PayPal (P3.2) =====

    /**
     * 创建 PayPal 订单。前端用返回的 paypalOrderId 调 PayPal JS SDK 弹窗。
     *
     * <p>两种用途：
     * <ul>
     *   <li>order_payment：支付 Shopify 订单（refId 必填）</li>
     *   <li>balance_recharge：余额充值（refId 不填）</li>
     * </ul>
     */
    @PostMapping("/paypal/create-order")
    public ResponseEntity<CreatePayPalOrderResponse> createPayPalOrder(
            HttpServletRequest httpRequest,
            @RequestBody CreatePayPalOrderRequest req) {
        Long userId = currentUserId(httpRequest);
        return ResponseEntity.ok(billingService.createPayPalOrder(userId, req));
    }

    /**
     * 捕获 PayPal 订单。前端在 PayPal JS SDK onApprove 回调中调用。
     * 幂等：PayPal orderId 全局唯一；重复调用返回同一结果。
     */
    @PostMapping("/paypal/{paypalOrderId}/capture")
    public ResponseEntity<CapturePayPalOrderResponse> capturePayPalOrder(
            HttpServletRequest httpRequest,
            @PathVariable String paypalOrderId) {
        Long userId = currentUserId(httpRequest);
        return ResponseEntity.ok(billingService.capturePayPalOrder(userId, paypalOrderId));
    }

    /**
     * 创建月订 PayPal 订单（purpose=subscribe）。捕获后发放月订积分（§5 / D5）。
     */
    @PostMapping("/paypal/create-subscription")
    public ResponseEntity<CreatePayPalOrderResponse> createSubscription(
            HttpServletRequest httpRequest,
            @RequestBody CreateSubscriptionRequest req) {
        Long userId = currentUserId(httpRequest);
        return ResponseEntity.ok(billingService.createSubscriptionOrder(userId, req));
    }

    /**
     * 创建加购包 PayPal 订单（purpose=credit_pack）。捕获后发放加购积分（§5 / D5）。
     */
    @PostMapping("/paypal/create-pack-order")
    public ResponseEntity<CreatePayPalOrderResponse> createPackOrder(
            HttpServletRequest httpRequest,
            @RequestBody CreatePackOrderRequest req) {
        Long userId = currentUserId(httpRequest);
        return ResponseEntity.ok(billingService.createPackOrder(userId, req));
    }

    // ===== Payment Orders (P3.5) =====

    /**
     * 查询当前用户的支付订单列表（分页）。
     * 支持按 status 过滤（created/approved/capturing/captured/failed）。
     */
    @GetMapping("/orders")
    public ResponseEntity<PaymentOrderListResponse> listPaymentOrders(
            HttpServletRequest httpRequest,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        Long userId = currentUserId(httpRequest);
        return ResponseEntity.ok(billingService.listPaymentOrders(userId, status, limit, offset));
    }

    /** 查询单个支付订单详情（仅返回属于当前用户的订单）。 */
    @GetMapping("/orders/{id}")
    public ResponseEntity<PaymentOrderItem> getPaymentOrder(
            HttpServletRequest httpRequest,
            @PathVariable Long id) {
        Long userId = currentUserId(httpRequest);
        return ResponseEntity.ok(billingService.getPaymentOrder(userId, id));
    }

    // ===== Helpers =====

    private Long currentUserId(HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        if (userId == null) {
            // 不应发生：JwtAuthFilter 已在受保护路径上注入 userId。
            throw new com.tang.common.core.exception.CustomException(
                    "Unauthorized: login required", 401, "UNAUTHENTICATED");
        }
        return userId;
    }
}
