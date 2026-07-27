package com.tang.plugin.service.billing;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.entity.user.CreditLot;
import com.tang.plugin.domain.entity.user.CreditTransaction;
import com.tang.plugin.domain.entity.user.SubscriptionPlan;
import com.tang.plugin.domain.entity.user.CreditPackage;
import com.tang.plugin.domain.entity.user.UserCredit;
import com.tang.plugin.domain.entity.user.UserSubscription;
import com.tang.plugin.dto.billing.BillingDtos;
import com.tang.plugin.dto.billing.BillingDtos.CreditLotItem;
import com.tang.plugin.dto.billing.BillingDtos.CreditLotListResponse;
import com.tang.plugin.dto.billing.BillingDtos.CreditOverview;
import com.tang.plugin.dto.billing.BillingDtos.CreditTransactionItem;
import com.tang.plugin.dto.billing.BillingDtos.CreditTransactionListResponse;
import com.tang.plugin.dto.billing.BillingDtos.ConsumeCreditsRequest;
import com.tang.plugin.dto.billing.BillingDtos.ConsumeCreditsResult;
import com.tang.plugin.dto.billing.BillingDtos.CreditBucketBreakdown;
import com.tang.plugin.dto.billing.BillingDtos.GrantCreditsRequest;
import com.tang.plugin.dto.billing.BillingDtos.GrantCreditsResult;
import com.tang.plugin.dto.billing.BillingDtos.MarketingChargeResult;
import com.tang.plugin.dto.billing.BillingDtos.WelcomeClaimResponse;
import com.tang.plugin.repository.CreditLotRepository;
import com.tang.plugin.repository.CreditTransactionRepository;
import com.tang.plugin.repository.CreditPackageRepository;
import com.tang.plugin.repository.SubscriptionPlanRepository;
import com.tang.plugin.repository.UserCreditRepository;
import com.tang.plugin.repository.UserSubscriptionRepository;
import com.tang.plugin.repository.UserWelcomeClaimRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 积分账户业务逻辑（P4）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>账户懒创建：首次访问 /billing/credits/balance 时由 {@link #ensureAccount} 创建。</li>
 *   <li>消耗走 FIFO 批次扣减 + 原子账户余额扣减，事务包裹保证一致。</li>
 *   <li>发放同时写 lot + 调整账户余额，事务包裹。</li>
 *   <li>幂等性：当前实现不保证消费幂等，调用方需自行去重。
 *       P5/P6 阶段引入 idempotency_key 时再扩展。</li>
 * </ul>
 *
 * <p>并发安全：
 * <ul>
 *   <li>账户余额扣减使用原子 UPDATE（WHERE balance >= ?）防止透支。</li>
 *   <li>批次扣减使用 SELECT + UPDATE（事务内），LEAST 兜底防止超扣。</li>
 * </ul>
 */
@Slf4j
@Service
public class CreditService {

    /** 单次发放上限（防止误操作）。 */
    private static final int MAX_GRANT_PER_CALL = 1_000_000;
    /** 单次消耗上限。 */
    private static final int MAX_CONSUME_PER_CALL = 100_000;

    @Resource
    private UserCreditRepository creditAccountRepository;

    @Resource
    private CreditTransactionRepository txnRepository;

    @Resource
    private CreditLotRepository lotRepository;

    @Resource
    private UserWelcomeClaimRepository welcomeClaimRepository;

    @Resource
    private SubscriptionPlanRepository planRepository;

    @Resource
    private CreditPackageRepository packRepository;

    @Resource
    private UserSubscriptionRepository subscriptionRepository;

    @Resource
    private TransactionTemplate transactionTemplate;

    // ===== Overview =====

    public CreditOverview getOverview(Long userId) {
        UserCredit acc = ensureAccount(userId);
        return new CreditOverview(
                acc.getUserId(),
                acc.getBalanceCredits(),
                acc.getTotalGranted(),
                acc.getTotalConsumed(),
                acc.getTotalExpired()
        );
    }

    /** 仅查余额，不懒创建（供护栏检查使用）。 */
    public Integer getBalance(Long userId) {
        return creditAccountRepository.findByUserId(userId)
                .map(UserCredit::getBalanceCredits)
                .orElse(0);
    }

    public UserCredit ensureAccount(Long userId) {
        return creditAccountRepository.insertIfAbsent(userId);
    }

    // ===== Consume =====

    /**
     * 消耗积分。事务内：原子扣减账户余额 + FIFO 扣减批次 + 写流水。
     *
     * <p>如果余额不足，整个事务回滚，返回 success=false。
     * 如果扣减成功但批次扣减或流水写入失败（极少），事务回滚，余额不变。
     *
     * <p>幂等性：调用方需自行去重（同一 endpoint+refId 多次调用会每次扣减）。
     *
     * @return ConsumeCreditsResult.success=true 表示扣减成功
     */
    public ConsumeCreditsResult consumeCredits(Long userId, ConsumeCreditsRequest req) {
        if (req == null) {
            throw new CustomException("Request body is required", 400, "INVALID_REQUEST");
        }
        if (StringUtils.isBlank(req.endpoint())) {
            throw new CustomException("endpoint is required", 400, "ENDPOINT_REQUIRED");
        }
        if (req.amount() == null || req.amount() <= 0) {
            throw new CustomException("amount must be positive", 400, "INVALID_AMOUNT");
        }
        if (req.amount() > MAX_CONSUME_PER_CALL) {
            throw new CustomException("amount exceeds single-call limit " + MAX_CONSUME_PER_CALL,
                    400, "AMOUNT_TOO_LARGE");
        }

        // 先检查余额（避免无谓的事务开销）
        Integer currentBalance = getBalance(userId);
        if (currentBalance == null || currentBalance < req.amount()) {
            return new ConsumeCreditsResult(false, currentBalance, null, "INSUFFICIENT_CREDITS");
        }

        // 事务内扣减
        try {
            Integer[] balanceAfterHolder = new Integer[]{null};
            Long[] txnIdHolder = new Long[]{null};

            transactionTemplate.executeWithoutResult(status -> {
                // 1) 原子扣减账户余额（防透支）
                int updated = creditAccountRepository.tryConsume(userId, req.amount());
                if (updated == 0) {
                    // 并发场景下余额已被其他请求扣减
                    throw new CustomException("Insufficient credits (concurrent)",
                            409, "INSUFFICIENT_CREDITS");
                }

                // 2) FIFO 扣减批次（事务内）
                int remainingToConsume = req.amount();
                List<CreditLot> lots = lotRepository.listConsumable(userId);
                for (CreditLot lot : lots) {
                    if (remainingToConsume <= 0) break;
                    int consumed = lotRepository.consumeFromLot(lot.getId(), remainingToConsume);
                    remainingToConsume -= consumed;
                }
                if (remainingToConsume > 0) {
                    // 理论不应发生（账户余额已扣减成功，批次总和应当 ≥ 余额）
                    // 但若发生过期任务未及时更新账户余额，可能出现这种情况
                    log.error("Credit lot total < account balance: userId={} amount={} missing={}",
                            userId, req.amount(), remainingToConsume);
                    throw new IllegalStateException("Credit lot inconsistency for userId=" + userId);
                }

                // 3) 写流水
                UserCredit after = creditAccountRepository.findByUserId(userId)
                        .orElseThrow(() -> new IllegalStateException("Account disappeared"));
                Integer balanceBefore = after.getBalanceCredits() + req.amount();
                Integer balanceAfter = after.getBalanceCredits();

                CreditTransaction txn = new CreditTransaction()
                        .setUserId(userId)
                        .setType("consume")
                        .setAmount(-req.amount())
                        .setBalanceBefore(balanceBefore)
                        .setBalanceAfter(balanceAfter)
                        .setRefType(req.refType() != null ? req.refType() : "marketing_api")
                        .setRefId(req.refId())
                        .setEndpoint(req.endpoint())
                        .setRemark(req.remark());
                txnRepository.insert(txn);

                balanceAfterHolder[0] = balanceAfter;
                txnIdHolder[0] = txn.getId();
            });

            log.info("Credits consumed: userId={} endpoint={} amount={} balanceAfter={}",
                    userId, req.endpoint(), req.amount(), balanceAfterHolder[0]);
            return new ConsumeCreditsResult(true, balanceAfterHolder[0], txnIdHolder[0], null);
        } catch (CustomException e) {
            if ("INSUFFICIENT_CREDITS".equals(e.getCode())) {
                return new ConsumeCreditsResult(false, currentBalance, null, "INSUFFICIENT_CREDITS");
            }
            throw e;
        }
    }

    // ===== Grant (P4 测试用，P5 接入支付后弃用) =====

    /**
     * 发放积分。事务内：写 lot + 调整账户余额 + 写流水。
     *
     * <p>用途：P4 阶段无支付入口，用于测试运营中心扣减流程。
     * P5 接入订阅/积分包后，由订阅激活/积分包购买流程调用此方法。
     *
     * <p>幂等性：调用方需自行去重（同一 sourceId 多次调用会重复发放）。
     */
    public GrantCreditsResult grantCredits(Long userId, GrantCreditsRequest req) {
        if (req == null) {
            throw new CustomException("Request body is required", 400, "INVALID_REQUEST");
        }
        if (req.amount() == null || req.amount() <= 0) {
            throw new CustomException("amount must be positive", 400, "INVALID_AMOUNT");
        }
        if (req.amount() > MAX_GRANT_PER_CALL) {
            throw new CustomException("amount exceeds single-call limit " + MAX_GRANT_PER_CALL,
                    400, "AMOUNT_TOO_LARGE");
        }
        String sourceType = StringUtils.isBlank(req.sourceType()) ? "manual" : req.sourceType();
        Instant expiresAt = parseExpiresAt(req.expiresAtStr());

        // 确保账户存在
        ensureAccount(userId);

        Integer[] balanceAfterHolder = new Integer[]{null};
        Long[] lotIdHolder = new Long[]{null};
        Long[] txnIdHolder = new Long[]{null};

        transactionTemplate.executeWithoutResult(status -> {
            // 1) 写批次
            CreditLot lot = new CreditLot()
                    .setUserId(userId)
                    .setSourceType(sourceType)
                    .setSourceId(req.sourceId())
                    .setAmountGranted(req.amount())
                    .setExpiresAt(expiresAt);
            lotRepository.insert(lot);
            lotIdHolder[0] = lot.getId();

            // 2) 调整账户余额
            int adjusted = creditAccountRepository.addCredits(userId, req.amount());
            if (adjusted == 0) {
                throw new IllegalStateException("addCredits affected 0 rows for userId=" + userId);
            }

            // 3) 写流水
            UserCredit after = creditAccountRepository.findByUserId(userId)
                    .orElseThrow(() -> new IllegalStateException("Account disappeared"));
            Integer balanceBefore = after.getBalanceCredits() - req.amount();
            Integer balanceAfter = after.getBalanceCredits();

            CreditTransaction txn = new CreditTransaction()
                    .setUserId(userId)
                    .setType("grant")
                    .setAmount(req.amount())
                    .setBalanceBefore(balanceBefore)
                    .setBalanceAfter(balanceAfter)
                    .setRefType(sourceType)
                    .setRefId(req.sourceId() != null ? String.valueOf(req.sourceId()) : null)
                    .setRemark(req.remark());
            txnRepository.insert(txn);

            balanceAfterHolder[0] = balanceAfter;
            txnIdHolder[0] = txn.getId();
        });

        log.info("Credits granted: userId={} sourceType={} amount={} balanceAfter={} lotId={}",
                userId, sourceType, req.amount(), balanceAfterHolder[0], lotIdHolder[0]);
        return new GrantCreditsResult(true, balanceAfterHolder[0], lotIdHolder[0], txnIdHolder[0], req.amount());
    }

    private Instant parseExpiresAt(String expiresAtStr) {
        if (StringUtils.isBlank(expiresAtStr)) return null;
        try {
            return Instant.parse(expiresAtStr);
        } catch (DateTimeParseException e) {
            throw new CustomException("expiresAtStr must be ISO-8601 (e.g. 2026-12-31T23:59:59Z)",
                    400, "INVALID_EXPIRES_AT");
        }
    }

    // ===== Transactions / Lots =====

    public CreditTransactionListResponse listTransactions(Long userId, String type, int limit, int offset) {
        List<CreditTransaction> txns = txnRepository.listByUser(userId, type, limit, offset);
        int total = txnRepository.countByUser(userId, type);
        List<CreditTransactionItem> items = txns.stream()
                .map(t -> new CreditTransactionItem(
                        t.getId(), t.getType(), t.getAmount(),
                        t.getBalanceBefore(), t.getBalanceAfter(),
                        t.getRefType(), t.getRefId(), t.getEndpoint(), t.getRemark(),
                        t.getIdempotencyKey(), t.getBucket(), t.getBucketsJson(), t.getUpstreamCredits(),
                        t.getCreatedAt()))
                .collect(Collectors.toList());
        return new CreditTransactionListResponse(items, total, limit, offset);
    }

    public CreditLotListResponse listLots(Long userId, int limit, int offset) {
        List<CreditLot> lots = lotRepository.listByUser(userId, limit, offset);
        int total = lotRepository.countByUser(userId);
        List<CreditLotItem> items = lots.stream()
                .map(l -> new CreditLotItem(
                        l.getId(), l.getSourceType(), l.getSourceId(),
                        l.getAmountGranted(), l.getAmountConsumed(), l.getAmountExpired(),
                        l.getAmountGranted() - l.getAmountConsumed() - l.getAmountExpired(),
                        l.getExpiresAt(), l.getCreatedAt()))
                .collect(Collectors.toList());
        return new CreditLotListResponse(items, total, limit, offset);
    }

    // ===== 商业化（§4 双桶 / 欢迎分 / 订阅发放） =====

    /** 欢迎分额度（§4.2）。 */
    private static final int WELCOME_CREDITS = 30;

    /**
     * 双桶拆分（§4.5）：免费分 vs 付费分（月订+加购+促销）。
     * 顶栏 / UsageCard / 个人中心钱包共用此结构。
     */
    public CreditBucketBreakdown getBucketBreakdown(Long userId) {
        ensureAccount(userId);
        java.util.Map<String, Integer> bySource = lotRepository.sumRemainingBySourceType(userId);
        int welcome = bySource.getOrDefault("welcome", 0);
        int promo = bySource.getOrDefault("promo", 0);
        int subscription = bySource.getOrDefault("sub_starter", 0)
                + bySource.getOrDefault("sub_growth", 0)
                + bySource.getOrDefault("subscription", 0);
        int pack = bySource.getOrDefault("pack_boost", 0)
                + bySource.getOrDefault("credit_pack", 0);
        int free = welcome;
        int paid = promo + subscription + pack;
        int balance = free + paid;
        return new CreditBucketBreakdown(userId, balance, free, paid, subscription, pack, promo);
    }

    /**
     * 营销调用扣费（§4.3 服务端权威扣减）。
     * 实扣 = 上游 U × 2；按 free→promo→subscription→credit_pack 优先级 FIFO 扣减；
     * 写流水（bucket + upstream_credits + idempotency_key）。
     *
     * @param upstreamU 上游实际消耗 U（pipispy consumed_credits）；必须 > 0
     * @param uri       调用的 pipispy URI（用于幂等键）
     * @param cacheKey  客户端缓存键（用于幂等键；可为 null）
     * @param endpoint  接口名（流水 endpoint 字段）
     * @param refId     实体 id（流水 ref_id；可为 null）
     * @return MarketingChargeResult
     * @throws CustomException 余额不足时抛 402 INSUFFICIENT_CREDITS
     */
    public MarketingChargeResult chargeMarketingCall(Long userId, int upstreamU, String uri,
                                                     String cacheKey, String endpoint, String refId) {
        if (upstreamU <= 0) {
            throw new CustomException("upstreamU must be positive", 400, "INVALID_AMOUNT");
        }
        int amount = upstreamU * 2; // 计价铁律：向用户收取 = U × 2

        // 幂等：相同 userId|uri|cacheKey|day 已扣过则直接返回，不重复扣。
        String idemKey = buildIdempotencyKey(userId, uri, cacheKey);
        CreditTransaction existing = txnRepository.findByIdempotencyKey(userId, idemKey);
        if (existing != null) {
            int charged = existing.getAmount() != null ? -existing.getAmount() : amount;
            return new MarketingChargeResult(userId, upstreamU, charged,
                    existing.getBucket(), existing.getBalanceAfter(), existing.getId());
        }

        Integer currentBalance = getBalance(userId);
        if (currentBalance == null || currentBalance < amount) {
            throw new CustomException("Insufficient credits for marketing call (need " + amount
                    + ", have " + currentBalance + ")", 402, "INSUFFICIENT_CREDITS");
        }

        Integer[] balanceAfterHolder = new Integer[]{null};
        Long[] txnIdHolder = new Long[]{null};
        String[] bucketHolder = new String[]{null};
        String[] bucketsJsonHolder = new String[]{null};

        transactionTemplate.executeWithoutResult(status -> {
            int updated = creditAccountRepository.tryConsume(userId, amount);
            if (updated == 0) {
                throw new CustomException("Insufficient credits (concurrent)", 409, "INSUFFICIENT_CREDITS");
            }
            int remainingToConsume = amount;
            List<CreditLot> lots = lotRepository.listConsumable(userId);
            // 收集跨桶扣减路径：[{bucket, amount}, ...]
            java.util.List<java.util.Map<String, Object>> walkPath = new java.util.ArrayList<>();
            for (CreditLot lot : lots) {
                if (remainingToConsume <= 0) break;
                int consumed = lotRepository.consumeFromLot(lot.getId(), remainingToConsume);
                if (consumed > 0) {
                    String bucket = toBucket(lot.getSourceType());
                    if (bucketHolder[0] == null) {
                        bucketHolder[0] = bucket;
                    }
                    walkPath.add(java.util.Map.of("bucket", bucket, "amount", consumed));
                    remainingToConsume -= consumed;
                }
            }
            if (remainingToConsume > 0) {
                log.error("Credit lot total < account balance after consume: userId={} amount={} missing={}",
                        userId, amount, remainingToConsume);
                throw new IllegalStateException("Credit lot inconsistency for userId=" + userId);
            }
            // 跨桶时写完整路径 JSON；单桶时留 null（bucket 字段已足够）
            if (walkPath.size() > 1) {
                try {
                    bucketsJsonHolder[0] = new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(walkPath);
                } catch (Exception e) {
                    log.warn("Failed to serialize walkPath for userId={}", userId, e);
                }
            }
            UserCredit after = creditAccountRepository.findByUserId(userId)
                    .orElseThrow(() -> new IllegalStateException("Account disappeared"));
            Integer balanceBefore = after.getBalanceCredits() + amount;
            Integer balanceAfter = after.getBalanceCredits();
            CreditTransaction txn = new CreditTransaction()
                    .setUserId(userId)
                    .setType("consume")
                    .setAmount(-amount)
                    .setBalanceBefore(balanceBefore)
                    .setBalanceAfter(balanceAfter)
                    .setRefType("marketing_api")
                    .setRefId(refId)
                    .setEndpoint(endpoint)
                    .setIdempotencyKey(idemKey)
                    .setBucket(bucketHolder[0])
                    .setBucketsJson(bucketsJsonHolder[0])
                    .setUpstreamCredits(upstreamU)
                    .setRemark("uri=" + uri);
            txnRepository.insert(txn);
            balanceAfterHolder[0] = balanceAfter;
            txnIdHolder[0] = txn.getId();
        });

        log.info("Marketing charged: userId={} uri={} upstreamU={} amount={} bucket={} balanceAfter={}",
                userId, uri, upstreamU, amount, bucketHolder[0], balanceAfterHolder[0]);
        return new MarketingChargeResult(userId, upstreamU, amount, bucketHolder[0],
                balanceAfterHolder[0], txnIdHolder[0]);
    }

    /**
     * 领取欢迎分（§4.2）。幂等：已领取返回 alreadyClaimed=true 且不重复发放。
     */
    public WelcomeClaimResponse claimWelcome(Long userId) {
        boolean inserted = welcomeClaimRepository.insertIfAbsent(userId);
        if (!inserted) {
            return new WelcomeClaimResponse(false, true, 0, getBalance(userId));
        }
        Instant expires = Instant.now().plus(java.time.Duration.ofDays(90));
        transactionTemplate.executeWithoutResult(status -> {
            CreditLot lot = new CreditLot()
                    .setUserId(userId)
                    .setSourceType("welcome")
                    .setSourceId(null)
                    .setAmountGranted(WELCOME_CREDITS)
                    .setExpiresAt(expires);
            lotRepository.insert(lot);
            creditAccountRepository.addCredits(userId, WELCOME_CREDITS);
            UserCredit after = creditAccountRepository.findByUserId(userId)
                    .orElseThrow(() -> new IllegalStateException("Account disappeared"));
            Integer balanceBefore = after.getBalanceCredits() - WELCOME_CREDITS;
            CreditTransaction txn = new CreditTransaction()
                    .setUserId(userId)
                    .setType("grant")
                    .setAmount(WELCOME_CREDITS)
                    .setBalanceBefore(balanceBefore)
                    .setBalanceAfter(after.getBalanceCredits())
                    .setRefType("welcome")
                    .setRefId(null)
                    .setBucket("welcome")
                    .setRemark("welcome bonus");
            txnRepository.insert(txn);
        });
        log.info("Welcome credits claimed: userId={} granted={}", userId, WELCOME_CREDITS);
        return new WelcomeClaimResponse(true, false, WELCOME_CREDITS, getBalance(userId));
    }

    /** 月订捕获后发放积分（§5 / D5）。 */
    public GrantCreditsResult grantSubscriptionCredits(Long userId, String planCode, Long paymentOrderId) {
        SubscriptionPlan plan = planRepository.findByCode(planCode);
        if (plan == null) {
            throw new CustomException("Unknown plan: " + planCode, 400, "UNKNOWN_PLAN");
        }
        boolean promo = plan.getPromoUntil() != null && plan.getPromoUntil().isAfter(Instant.now());
        int credits = promo ? plan.getCreditsPromo() : plan.getCreditsNormal();
        Instant expires = Instant.now().plus(java.time.Duration.ofDays(plan.getDurationDays()));
        grantLot(userId, planCode, credits, expires, "subscription");
        UserSubscription sub = new UserSubscription()
                .setUserId(userId)
                .setPlanCode(planCode)
                .setPaymentOrderId(paymentOrderId)
                .setStatus("active")
                .setCreditsGranted(credits)
                .setStartedAt(Instant.now())
                .setEndsAt(expires);
        subscriptionRepository.insert(sub);
        log.info("Subscription credits granted: userId={} plan={} credits={} promo={}",
                userId, planCode, credits, promo);
        return new GrantCreditsResult(true, getBalance(userId), null, null, credits);
    }

    /** 加购包捕获后发放积分（§5 / D5）。 */
    public GrantCreditsResult grantPackCredits(Long userId, String packageCode, Long paymentOrderId) {
        CreditPackage pkg = packRepository.findByCode(packageCode);
        if (pkg == null) {
            throw new CustomException("Unknown package: " + packageCode, 400, "UNKNOWN_PACKAGE");
        }
        boolean promo = pkg.getPromoUntil() != null && pkg.getPromoUntil().isAfter(Instant.now());
        int credits = promo ? pkg.getCreditsPromo() : pkg.getCreditsNormal();
        Instant expires = Instant.now().plus(java.time.Duration.ofDays(pkg.getDurationDays()));
        grantLot(userId, packageCode, credits, expires, "credit_pack");
        log.info("Pack credits granted: userId={} package={} credits={} promo={}",
                userId, packageCode, credits, promo);
        return new GrantCreditsResult(true, getBalance(userId), null, null, credits);
    }

    // ===== Helpers =====

    private void grantLot(Long userId, String sourceType, int credits, Instant expiresAt, String bucket) {
        ensureAccount(userId);
        transactionTemplate.executeWithoutResult(status -> {
            CreditLot lot = new CreditLot()
                    .setUserId(userId)
                    .setSourceType(sourceType)
                    .setSourceId(null)
                    .setAmountGranted(credits)
                    .setExpiresAt(expiresAt);
            lotRepository.insert(lot);
            creditAccountRepository.addCredits(userId, credits);
            UserCredit after = creditAccountRepository.findByUserId(userId)
                    .orElseThrow(() -> new IllegalStateException("Account disappeared"));
            Integer balanceBefore = after.getBalanceCredits() - credits;
            CreditTransaction txn = new CreditTransaction()
                    .setUserId(userId)
                    .setType("grant")
                    .setAmount(credits)
                    .setBalanceBefore(balanceBefore)
                    .setBalanceAfter(after.getBalanceCredits())
                    .setRefType(sourceType)
                    .setRefId(null)
                    .setBucket(bucket)
                    .setRemark("grant " + sourceType);
            txnRepository.insert(txn);
        });
    }

    private String toBucket(String sourceType) {
        if (sourceType == null) return "manual";
        return switch (sourceType) {
            case "welcome" -> "welcome";
            case "promo" -> "promo";
            case "sub_starter", "sub_growth", "subscription" -> "subscription";
            case "pack_boost", "credit_pack" -> "credit_pack";
            default -> "manual";
        };
    }

    private String buildIdempotencyKey(Long userId, String uri, String cacheKey) {
        String day = java.time.LocalDate.now().toString(); // yyyy-MM-dd
        return userId + "|" + (uri == null ? "" : uri) + "|" + (cacheKey == null ? "" : cacheKey) + "|" + day;
    }
}
