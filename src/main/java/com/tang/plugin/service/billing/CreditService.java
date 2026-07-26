package com.tang.plugin.service.billing;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.entity.user.CreditLot;
import com.tang.plugin.domain.entity.user.CreditTransaction;
import com.tang.plugin.domain.entity.user.UserCredit;
import com.tang.plugin.dto.billing.BillingDtos;
import com.tang.plugin.dto.billing.BillingDtos.CreditLotItem;
import com.tang.plugin.dto.billing.BillingDtos.CreditLotListResponse;
import com.tang.plugin.dto.billing.BillingDtos.CreditOverview;
import com.tang.plugin.dto.billing.BillingDtos.CreditTransactionItem;
import com.tang.plugin.dto.billing.BillingDtos.CreditTransactionListResponse;
import com.tang.plugin.dto.billing.BillingDtos.ConsumeCreditsRequest;
import com.tang.plugin.dto.billing.BillingDtos.ConsumeCreditsResult;
import com.tang.plugin.dto.billing.BillingDtos.GrantCreditsRequest;
import com.tang.plugin.dto.billing.BillingDtos.GrantCreditsResult;
import com.tang.plugin.repository.CreditLotRepository;
import com.tang.plugin.repository.CreditTransactionRepository;
import com.tang.plugin.repository.UserCreditRepository;
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
        return new GrantCreditsResult(true, balanceAfterHolder[0], lotIdHolder[0], txnIdHolder[0]);
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
                        t.getIdempotencyKey(), t.getBucket(), t.getUpstreamCredits(),
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
}
