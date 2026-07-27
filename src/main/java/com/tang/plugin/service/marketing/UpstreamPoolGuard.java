package com.tang.plugin.service.marketing;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.dto.marketing.MarketingDtos.MarketingDataResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * L0（pipispy master_pool）低水位告警 + 硬熔断（Go-Live G6d）。
 *
 * <p>与用户钱包解耦：此余额永不作为终端用户顶栏数字。
 * 付费营销调用前 assert；耗尽时明确拒绝，避免用户有分却空烧预期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpstreamPoolGuard {

    private final PipispyClient pipispyClient;

    /** 软阈值：低于此写 WARN 告警，仍放行。 */
    @Value("${tang.plugin.pipispy.l0-soft-threshold:500}")
    private int softThreshold;

    /** 硬阈值：低于等于此拒绝付费调用。 */
    @Value("${tang.plugin.pipispy.l0-hard-threshold:0}")
    private int hardThreshold;

    /** 余额缓存毫秒，避免每次付费调用打爆上游。 */
    @Value("${tang.plugin.pipispy.l0-cache-ms:60000}")
    private long cacheMs;

    private volatile Snapshot snapshot;

    /**
     * 付费 URI 调用前检查。免费端点 / 3 日窗不应调用本方法。
     *
     * @throws CustomException {@code UPSTREAM_CREDITS_EXHAUSTED} 当 L0 ≤ 硬阈值
     */
    public void assertAvailableForPaidCall() {
        Integer remaining = peekRemaining();
        if (remaining == null) {
            // 拉余额失败：不熔断（上游调用本身会失败），但打告警便于运维。
            log.warn("L0 pool balance unknown (fetch failed); allowing paid call with caution");
            return;
        }
        if (remaining <= hardThreshold) {
            log.error("L0 pool exhausted: remaining={} hardThreshold={}", remaining, hardThreshold);
            throw new CustomException(
                    "Upstream intelligence pool is temporarily unavailable. Please try again later.",
                    503,
                    "UPSTREAM_CREDITS_EXHAUSTED");
        }
        if (remaining < softThreshold) {
            log.warn("L0 pool low water: remaining={} softThreshold={}", remaining, softThreshold);
        }
    }

    /** 供运维/测试读取最近一次缓存的 L0 余额；可能为 null。 */
    public Integer cachedRemainingOrNull() {
        Snapshot s = snapshot;
        return s == null ? null : s.remaining;
    }

    private Integer peekRemaining() {
        long now = System.currentTimeMillis();
        Snapshot s = snapshot;
        if (s != null && now - s.atMs < cacheMs) {
            return s.remaining;
        }
        synchronized (this) {
            s = snapshot;
            if (s != null && now - s.atMs < cacheMs) {
                return s.remaining;
            }
            Integer remaining = fetchRemaining();
            snapshot = new Snapshot(remaining, System.currentTimeMillis());
            return remaining;
        }
    }

    private Integer fetchRemaining() {
        try {
            MarketingDataResponse res = pipispyClient.fetchCreditsBalance();
            if (res == null || !res.ok()) {
                return null;
            }
            // 信封 remaining_credits；若缺则尝试 data 内字段由 mapper 侧已摊平到 remainingCredits
            return res.remainingCredits();
        } catch (Exception e) {
            log.warn("L0 balance fetch threw", e);
            return null;
        }
    }

    private record Snapshot(Integer remaining, long atMs) {}
}
