package com.tang.plugin.service.match;

import com.alibaba.fastjson2.JSON;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.match.ConfirmImageMatchDTO;
import com.tang.plugin.domain.dto.match.MatchJobProgressVO;
import com.tang.plugin.domain.dto.match.image.ImageSearchProductVO;
import com.tang.plugin.domain.dto.match.image.ImageSearchResultVO;
import com.tang.plugin.domain.entity.match.ShopMatchJob;
import com.tang.plugin.domain.entity.match.ShopProductBinding;
import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import com.tang.plugin.enums.match.MatchJobStatus;
import com.tang.plugin.repository.ShopMatchJobRepository;
import com.tang.plugin.repository.ShopProductBindingRepository;
import com.tang.plugin.repository.ThirdPlatformProductRepository;
import com.tang.plugin.service.match.image.ImageMatchConfirmService;
import com.tang.plugin.service.match.image.ImageSearchService;
import com.tang.plugin.service.publish.CatalogPublishLinkService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Server-side queue for post-auth / manual image-auto-match. Persists progress so the frontend can
 * poll a real progress bar. All auto-links land as PENDING for human batch/single confirm.
 */
@Slf4j
@Service
public class MatchQueueService {

    private static final int SEARCH_LIMIT = 5;
    private static final int RECENT_MAX = 6;
    private static final String JOB_TYPE = "IMAGE_AUTO_MATCH";

    @Resource
    private ShopMatchJobRepository shopMatchJobRepository;
    @Resource
    private ThirdPlatformProductRepository thirdPlatformProductRepository;
    @Resource
    private ShopProductBindingRepository shopProductBindingRepository;
    @Resource
    private ImageSearchService imageSearchService;
    @Resource
    private ImageMatchConfirmService imageMatchConfirmService;
    @Resource
    private CatalogPublishLinkService catalogPublishLinkService;

    /**
     * Start (or return) a background image-match job for a shop. Idempotent while RUNNING/PENDING.
     */
    public MatchJobProgressVO startImageAutoMatch(String shopName, String scopeItemId) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("match queue requires shopName");
        }
        Optional<ShopMatchJob> active = shopMatchJobRepository.findActiveByShop(shopName);
        if (active.isPresent()) {
            return toProgress(active.get());
        }
        ShopMatchJob job = new ShopMatchJob()
                .setShopName(shopName)
                .setJobType(JOB_TYPE)
                .setStatus(MatchJobStatus.PENDING)
                .setScopeItemId(StringUtils.trimToNull(scopeItemId));
        Long id = shopMatchJobRepository.insert(job);
        if (id == null) {
            throw new CustomException("failed to create match job");
        }
        runJobAsync(id);
        return toProgress(shopMatchJobRepository.findById(id).orElseThrow());
    }

    public MatchJobProgressVO getProgress(Long jobId) {
        ShopMatchJob job = shopMatchJobRepository.findById(jobId)
                .orElseThrow(() -> new CustomException("match job not found: " + jobId));
        return toProgress(job);
    }

    public MatchJobProgressVO getActiveProgress(String shopName) {
        return shopMatchJobRepository.findActiveByShop(shopName)
                .map(this::toProgress)
                .orElse(null);
    }

    @Async
    public void runJobAsync(Long jobId) {
        ShopMatchJob job = shopMatchJobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != MatchJobStatus.PENDING) {
            return;
        }
        String shopName = job.getShopName();
        Deque<String> recent = new ArrayDeque<>();
        try {
            try {
                catalogPublishLinkService.backfillPublishedBindings(shopName);
            } catch (Exception e) {
                log.warn("Match queue backfill publish-bindings failed shopName={}: {}", shopName, e.getMessage());
            }

            Set<String> boundItems = new HashSet<>();
            for (ShopProductBinding b : shopProductBindingRepository.listBindableByShop(shopName)) {
                if (StringUtils.isNotBlank(b.getThirdPlatformItemId())) {
                    boundItems.add(b.getThirdPlatformItemId());
                }
            }

            List<ThirdPlatformProduct> targets = new ArrayList<>();
            for (ThirdPlatformProduct p : thirdPlatformProductRepository.listByShop(shopName)) {
                if (StringUtils.isNotBlank(job.getScopeItemId())
                        && !job.getScopeItemId().equals(p.getThirdPlatformItemId())) {
                    continue;
                }
                if (boundItems.contains(p.getThirdPlatformItemId())) {
                    continue;
                }
                if (StringUtils.isBlank(p.getPrimaryImageUrl())) {
                    continue;
                }
                targets.add(p);
            }

            int marked = shopMatchJobRepository.markRunning(jobId, targets.size());
            if (marked == 0) {
                return;
            }
            job = shopMatchJobRepository.findById(jobId).orElseThrow();

            if (targets.isEmpty()) {
                pushRecent(recent, "暂无待匹配商品（或缺主图）");
                finishJob(job, recent, MatchJobStatus.COMPLETED, null);
                return;
            }

            for (ThirdPlatformProduct p : targets) {
                job = shopMatchJobRepository.findById(jobId).orElseThrow();
                String name = StringUtils.defaultIfBlank(p.getTitle(), p.getThirdPlatformItemId());
                try {
                    ImageSearchResultVO res = imageSearchService.searchByShopProduct(
                            shopName, p.getThirdPlatformItemId(), SEARCH_LIMIT);
                    List<ImageSearchProductVO> items = res.getItems() == null ? List.of() : res.getItems();
                    if (items.isEmpty()) {
                        job.setSkippedCount(job.getSkippedCount() + 1);
                        pushRecent(recent, name + "：未召回货源");
                    } else {
                        int bestIdx = MatchCandidateRanker.pickBestIndex(items);
                        ImageSearchProductVO cand = items.get(bestIdx);
                        ConfirmImageMatchDTO dto = new ConfirmImageMatchDTO()
                                .setShopName(shopName)
                                .setThirdPlatformItemId(p.getThirdPlatformItemId())
                                .setOfferProductId(cand.getProductId())
                                .setOfferSkuId(cand.getSkuId())
                                .setDetailUrl(cand.getDetailUrl())
                                .setSimilarityScore(cand.getSimilarityScore())
                                .setImageSource(res.getImageSource())
                                .setQuerySource(res.getQuerySource())
                                .setAppliedQuery(res.getAppliedQuery())
                                .setOfferImageUrl(cand.getImageUrl())
                                .setOfferPrice(cand.getPrice())
                                .setAuto(true);
                        imageMatchConfirmService.confirm(dto);
                        job.setLinkedCount(job.getLinkedCount() + 1);
                        pushRecent(recent, name + "：已关联 " + StringUtils.defaultIfBlank(cand.getTitle(), cand.getProductId()) + "（待确认）");
                    }
                } catch (Exception e) {
                    job.setFailedCount(job.getFailedCount() + 1);
                    job.setLastError(e.getMessage());
                    pushRecent(recent, name + "：跳过");
                    log.warn("Match queue item failed shopName={} itemId={}: {}",
                            shopName, p.getThirdPlatformItemId(), e.getMessage());
                }
                job.setProcessedCount(job.getProcessedCount() + 1);
                job.setRecentJson(JSON.toJSONString(new ArrayList<>(recent)));
                shopMatchJobRepository.updateProgress(job);
            }
            finishJob(job, recent, MatchJobStatus.COMPLETED, null);
        } catch (Exception e) {
            log.error("Match queue job failed jobId={}", jobId, e);
            job = shopMatchJobRepository.findById(jobId).orElse(job);
            finishJob(job, recent, MatchJobStatus.FAILED, e.getMessage());
        }
    }

    private void finishJob(ShopMatchJob job, Deque<String> recent, MatchJobStatus status, String error) {
        if (job == null || job.getId() == null) {
            return;
        }
        job.setRecentJson(JSON.toJSONString(new ArrayList<>(recent)));
        shopMatchJobRepository.updateProgress(job);
        shopMatchJobRepository.markTerminal(job.getId(), status, error);
    }

    private void pushRecent(Deque<String> recent, String line) {
        recent.addFirst(line);
        while (recent.size() > RECENT_MAX) {
            recent.removeLast();
        }
    }

    private MatchJobProgressVO toProgress(ShopMatchJob job) {
        int total = Math.max(job.getTotalCount(), 0);
        int processed = Math.max(job.getProcessedCount(), 0);
        int percent = total > 0 ? Math.min(100, (int) Math.floor(processed * 100.0 / total)) : 0;
        if (job.getStatus() == MatchJobStatus.COMPLETED || job.getStatus() == MatchJobStatus.FAILED) {
            percent = 100;
        }
        List<String> recent = List.of();
        if (StringUtils.isNotBlank(job.getRecentJson())) {
            try {
                recent = JSON.parseArray(job.getRecentJson(), String.class);
            } catch (Exception ignored) {
                recent = List.of();
            }
        }
        return new MatchJobProgressVO()
                .setJobId(job.getId())
                .setShopName(job.getShopName())
                .setJobType(job.getJobType())
                .setJobStatus(job.getStatus().name())
                .setTotal(total)
                .setProcessed(processed)
                .setLinked(job.getLinkedCount())
                .setSkipped(job.getSkippedCount())
                .setFailed(job.getFailedCount())
                .setPercent(percent)
                .setLastError(job.getLastError())
                .setStartedAt(job.getStartedAt())
                .setFinishedAt(job.getFinishedAt())
                .setRecent(recent);
    }
}
