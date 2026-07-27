package com.tang.plugin.task;

import com.tang.plugin.repository.CreditLotRepository;
import com.tang.plugin.repository.UserCreditRepository;
import com.tang.plugin.repository.UserSubscriptionRepository;
import com.tang.plugin.domain.entity.user.UserSubscription;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 积分过期定时任务（§3.2 / §5）。
 *
 * <p>每天凌晨 3 点执行：
 * <ol>
 *   <li>将过期订阅对应的 user_subscriptions 状态置为 expired。</li>
 *   <li>将所有已过期但未标记 expired 的 credit_lots 余额置为 expired。</li>
 *   <li>同步从 user_credits.balance 扣除过期部分。</li>
 * </ol>
 */
@Slf4j
@Component
public class CreditExpirationTask {

    @Resource
    private CreditLotRepository lotRepository;

    @Resource
    private UserSubscriptionRepository subscriptionRepository;

    @Resource
    private UserCreditRepository creditAccountRepository;

    /**
     * 每天凌晨 3 点执行积分过期清理。
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void expireJob() {
        Instant started = Instant.now();
        log.info("Credit expiration job started at {}", started);
        int totalExpired = 0;
        int subsExpired = 0;

        try {
            // 1) 过期订阅状态
            List<UserSubscription> activeSubs = subscriptionRepository.findAllActive();
            for (UserSubscription sub : activeSubs) {
                if (sub.getEndsAt() != null && sub.getEndsAt().isBefore(Instant.now())) {
                    subscriptionRepository.markExpired(sub.getId());
                    subsExpired++;
                    log.info("Subscription expired: userId={} plan={} subId={} endedAt={}",
                            sub.getUserId(), sub.getPlanCode(), sub.getId(), sub.getEndsAt());
                }
            }

            // 2) 批次过期（按用户维度）
            List<Long> userIds = lotRepository.findUserIdsWithExpiringLots();
            for (Long userId : userIds) {
                // 先查询待过期总量，再标记过期，最后扣余额
                int expiringAmount = lotRepository.sumExpiringRemaining(userId);
                if (expiringAmount <= 0) continue;

                int affected = lotRepository.expireOverdueLots(userId);
                if (affected == 0) continue;

                int deductResult = creditAccountRepository.deductExpired(userId, expiringAmount);
                if (deductResult == 0) {
                    log.warn("deductExpired failed (balance < expiringAmount): userId={} amount={}",
                            userId, expiringAmount);
                } else {
                    totalExpired += expiringAmount;
                }
            }

            log.info("Credit expiration job completed in {}ms: {} credits expired, {} subscriptions expired",
                    Instant.now().toEpochMilli() - started.toEpochMilli(), totalExpired, subsExpired);
        } catch (Exception e) {
            log.error("Credit expiration job failed", e);
        }
    }
}
