package com.tang.plugin.config;

import com.tang.plugin.controller.ranking.RankingController;
import com.tang.plugin.repository.RankRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Free-tier Render guard: if ranking tables balloon (tens of thousands of rows from
 * Kalodata imports), list/import responses and JDBC buffers pressure the 512Mi heap
 * and the process is OOM-killed (exit 137) before binding {@code PORT}.
 *
 * On ready, prune to {@link RankingController#MAX_BOARD_SIZE} when over the soft cap.
 */
@Slf4j
@Component
public class RankingMemoryGuard {

    /** Soft trigger above the hard board size — prune only when clearly oversized. */
    private static final long PRUNE_TRIGGER = RankingController.MAX_BOARD_SIZE * 2L;

    @Resource
    private RankRepository rankRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void pruneIfOversized() {
        try {
            long count = rankRepository.countAllProducts();
            if (count <= PRUNE_TRIGGER) {
                log.info("Ranking memory guard: productCount={} (ok)", count);
                return;
            }
            log.warn(
                    "Ranking memory guard: productCount={} exceeds {}; pruning to {}",
                    count,
                    PRUNE_TRIGGER,
                    RankingController.MAX_BOARD_SIZE);
            int deleted = rankRepository.pruneToGlobalLimit(RankingController.MAX_BOARD_SIZE);
            log.warn(
                    "Ranking memory guard: pruned deleted={} remaining={}",
                    deleted,
                    rankRepository.countAllProducts());
        } catch (Exception e) {
            // Never block boot if ranking tables are missing / unreachable.
            log.warn("Ranking memory guard skipped: {}", e.toString());
        }
    }
}
