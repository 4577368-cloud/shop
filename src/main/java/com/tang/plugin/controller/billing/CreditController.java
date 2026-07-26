package com.tang.plugin.controller.billing;

import com.tang.plugin.dto.billing.BillingDtos.CreditLotListResponse;
import com.tang.plugin.dto.billing.BillingDtos.CreditBucketBreakdown;
import com.tang.plugin.dto.billing.BillingDtos.CreditOverview;
import com.tang.plugin.dto.billing.BillingDtos.WelcomeClaimResponse;
import com.tang.plugin.dto.billing.BillingDtos.CreditTransactionListResponse;
import com.tang.plugin.dto.billing.BillingDtos.ConsumeCreditsRequest;
import com.tang.plugin.dto.billing.BillingDtos.ConsumeCreditsResult;
import com.tang.plugin.dto.billing.BillingDtos.GrantCreditsRequest;
import com.tang.plugin.dto.billing.BillingDtos.GrantCreditsResult;
import com.tang.plugin.service.billing.CreditService;
import com.tang.plugin.service.user.AdminGuard;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 积分账户接口（P4）。
 *
 * <p>路径前缀 {@code /api/plugin/billing/**} 已在 {@code JwtAuthFilter} 中标记为受保护，
 * 所有请求必须携带有效的 {@code tb_access} cookie，过滤器会注入 {@code userId} 到 request attribute。
 *
 * <p>P4 范围：
 * <ul>
 *   <li>GET  /credits/balance        — 积分余额（运营中心调，替代 mock）</li>
 *   <li>GET  /credits/overview       — 积分账户概览</li>
 *   <li>GET  /credits/transactions   — 积分流水（分页 + 类型筛选）</li>
 *   <li>GET  /credits/lots           — 积分批次（含过期）</li>
 *   <li>POST /consume/credits        — 积分消耗（运营中心调用）</li>
 *   <li>POST /credits/grant          — 发放积分（P4 测试用，P5 接入支付后由订阅流程替代）</li>
 * </ul>
 *
 * <p>切换运营中心到真实计费：
 * 前端 {@code src/lib/marketing/api.ts} 的 {@code fetchCreditsBalance} 当前为 mock，
 * 设置 {@code NEXT_PUBLIC_MARKETING_REAL_BILLING=true} 后切到 {@code /credits/balance}。
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/billing")
public class CreditController {

    @Resource
    private CreditService creditService;
    @Resource
    private AdminGuard adminGuard;

    /**
     * 积分余额。运营中心调用此接口替代 mock。
     * 返回简单结构，便于直接喂给现有 {@code CreditsBalance} 类型。
     */
    @GetMapping("/credits/balance")
    public ResponseEntity<CreditBalanceResponse> balance(HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        Integer balance = creditService.getBalance(userId);
        return ResponseEntity.ok(new CreditBalanceResponse(userId, balance));
    }

    /** 积分账户概览（含累计统计）。 */
    @GetMapping("/credits/overview")
    public ResponseEntity<CreditOverview> overview(HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        return ResponseEntity.ok(creditService.getOverview(userId));
    }

    /** 积分流水（分页 + 类型筛选）。 */
    @GetMapping("/credits/transactions")
    public ResponseEntity<CreditTransactionListResponse> listTransactions(
            HttpServletRequest httpRequest,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        Long userId = currentUserId(httpRequest);
        return ResponseEntity.ok(creditService.listTransactions(userId, type, limit, offset));
    }

    /** 积分批次列表。 */
    @GetMapping("/credits/lots")
    public ResponseEntity<CreditLotListResponse> listLots(
            HttpServletRequest httpRequest,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        Long userId = currentUserId(httpRequest);
        return ResponseEntity.ok(creditService.listLots(userId, limit, offset));
    }

    /** 双桶拆分（§4.5）：免费分 vs 付费分，供顶栏 / UsageCard / 个人中心钱包复用。 */
    @GetMapping("/credits/buckets")
    public ResponseEntity<CreditBucketBreakdown> buckets(HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        return ResponseEntity.ok(creditService.getBucketBreakdown(userId));
    }

    /**
     * 领取欢迎分（§4.2）。幂等：已领取返回 alreadyClaimed=true 且不重复发放。
     * 成功发放 30 免费分（90 天有效）。
     */
    @PostMapping("/credits/welcome/claim")
    public ResponseEntity<WelcomeClaimResponse> claimWelcome(HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        return ResponseEntity.ok(creditService.claimWelcome(userId));
    }

    /**
     * 积分消耗。运营中心调用此接口扣减积分。
     * 成功返回 200 + ConsumeCreditsResult；余额不足返回 200 + success=false（非错误）。
     */
    @PostMapping("/consume/credits")
    public ResponseEntity<ConsumeCreditsResult> consumeCredits(
            HttpServletRequest httpRequest,
            @RequestBody ConsumeCreditsRequest req) {
        Long userId = currentUserId(httpRequest);
        ConsumeCreditsResult result = creditService.consumeCredits(userId, req);
        return ResponseEntity.ok(result);
    }

    /**
     * 发放积分（P4 测试用）。P5 接入支付后由订阅激活/积分包购买流程替代。
     * 当前默认允许登录用户自发放以便于联调；P5 将加管理员校验或下线。
     */
    @PostMapping("/credits/grant")
    public ResponseEntity<GrantCreditsResult> grantCredits(
            HttpServletRequest httpRequest,
            @RequestBody GrantCreditsRequest req) {
        Long userId = currentUserId(httpRequest);
        adminGuard.assertAdmin(userId);
        return ResponseEntity.ok(creditService.grantCredits(userId, req));
    }

    // ===== Helpers =====

    private Long currentUserId(HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        if (userId == null) {
            throw new com.tang.common.core.exception.CustomException(
                    "Unauthorized: login required", 401, "UNAUTHENTICATED");
        }
        return userId;
    }

    /** 简单余额响应（仅 userId + balance），供运营中心轻量调用。 */
    public record CreditBalanceResponse(Long userId, Integer balanceCredits) {}
}
