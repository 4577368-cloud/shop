package com.tang.plugin.service.skualign;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.match.SkuProductOverviewVO;
import com.tang.plugin.domain.dto.match.SkuVariantVO;
import com.tang.plugin.domain.dto.skualign.*;
import com.tang.plugin.domain.entity.skualign.AlignmentRun;
import com.tang.plugin.enums.skualign.AlignmentRunStatus;
import com.tang.plugin.enums.skualign.AlignmentTriggerType;
import com.tang.plugin.domain.entity.skualign.ProductSourceBinding;
import com.tang.plugin.domain.entity.skualign.VariantAlignmentReview;
import com.tang.plugin.domain.entity.skualign.VariantSkuBinding;
import com.tang.plugin.enums.skualign.VariantReviewState;
import com.tang.plugin.repository.skualign.AlignmentRunRepository;
import com.tang.plugin.repository.skualign.ProductSourceBindingRepository;
import com.tang.plugin.repository.skualign.VariantAlignmentReviewRepository;
import com.tang.plugin.repository.skualign.VariantSkuBindingRepository;
import com.tang.plugin.service.match.SkuBindingOverviewService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SKU Align V1 orchestration. Step 2–5 business logic lands here incrementally.
 *
 * <p>V1 bridge: overview/detail reads legacy {@link SkuBindingOverviewService} until V1 tables
 * are fully populated by alignment engine.
 */
@Slf4j
@Service
public class SkuAlignV1Service {

    private static final Duration PAGE_ENTER_STALE = Duration.ofMinutes(10);

    @Resource
    private SkuBindingOverviewService skuBindingOverviewService;
    @Resource
    private AlignmentRunRepository alignmentRunRepository;
    @Resource
    private VariantAlignmentReviewRepository reviewRepository;
    @Resource
    private VariantSkuBindingRepository v1BindingRepository;
    @Resource
    private ProductSourceBindingRepository productSourceRepository;
    @Resource
    private SkuAlignEngineService skuAlignEngineService;

    public SkuAlignOverviewVO overview(String shopName, String tab) {
        List<SkuProductOverviewVO> legacy = skuBindingOverviewService.overview(shopName);
        Map<String, Map<String, VariantAlignmentReview>> reviewsByProduct = reviewRepository.mapByShop(shopName);
        SkuAlignOverviewVO out = new SkuAlignOverviewVO()
                .setTotalProducts(legacy.size())
                .setItems(new ArrayList<>());

        int totalVariants = 0;
        int unresolved = 0;
        int suggested = 0;
        int unmapped = 0;
        int noSource = 0;
        int alignedProducts = 0;

        for (SkuProductOverviewVO p : legacy) {
            Map<String, VariantAlignmentReview> reviews =
                    reviewsByProduct.getOrDefault(p.getThirdPlatformItemId(), Map.of());
            ProductSourceBinding psb = productSourceRepository.findByProduct(shopName, p.getThirdPlatformItemId())
                    .orElse(null);
            SkuAlignProductSummaryVO summary = mapProductSummary(p, reviews, psb);
            out.getItems().add(summary);
            totalVariants += summary.getTotalVariants() != null ? summary.getTotalVariants() : 0;
            int u = countUnresolved(p);
            unresolved += u;
            if (u == 0 && summary.getTotalVariants() != null && summary.getTotalVariants() > 0) {
                alignedProducts++;
            }
            // V1 counters from review table when present; else bridge legacy bound heuristic
            suggested += summary.getSuggestedVariants() != null ? summary.getSuggestedVariants() : 0;
            unmapped += summary.getUnmappedVariants() != null ? summary.getUnmappedVariants() : 0;
            noSource += summary.getNoSourceVariants() != null ? summary.getNoSourceVariants() : 0;
        }

        return out
                .setTotalVariants(totalVariants)
                .setUnresolvedVariantsCount(unresolved)
                .setSuggestedCount(suggested)
                .setUnmappedCount(unmapped)
                .setNoSourceCount(noSource)
                .setAlignedProductsCount(alignedProducts);
    }

    public SkuAlignProductDetailVO productDetail(String shopName, String productId) {
        List<SkuProductOverviewVO> legacy = skuBindingOverviewService.overview(shopName);
        SkuProductOverviewVO product = legacy.stream()
                .filter(p -> productId.equals(p.getThirdPlatformItemId()))
                .findFirst()
                .orElseThrow(() -> new CustomException("PRODUCT_NOT_FOUND: 未找到该商品"));

        Map<String, VariantAlignmentReview> reviews = reviewRepository.mapByProduct(shopName, productId);
        Map<String, VariantSkuBinding> bindings = v1BindingRepository.mapActiveByProduct(shopName, productId);
        ProductSourceBinding psb = productSourceRepository.findByProduct(shopName, productId).orElse(null);

        SkuAlignProductDetailVO detail = new SkuAlignProductDetailVO()
                .setSummary(mapProductSummary(product, reviews, psb))
                .setPrimaryOffer(new SkuAlignOfferSummaryVO()
                        .setOfferId(product.getTangbuyProductId())
                        .setDetailUrl(product.getDetailUrl()))
                .setVariants(new ArrayList<>());

        if (product.getVariants() != null) {
            for (SkuVariantVO v : product.getVariants()) {
                detail.getVariants().add(mapVariantRow(v,
                        reviews.get(v.getThirdPlatformSkuId()),
                        bindings.get(v.getThirdPlatformSkuId()),
                        psb));
            }
        }
        if (psb != null && SkuOfferScopeHelper.hasSupplementOffer(psb.getSupplementalOfferIdsJson())) {
            String supplementOfferId = SkuOfferScopeHelper.parseSupplementOfferId(
                    psb.getSupplementalOfferIdsJson());
            detail.setSupplementOffer(new SkuAlignOfferSummaryVO()
                    .setOfferId(supplementOfferId)
                    .setDetailUrl("https://detail.1688.com/offer/" + supplementOfferId + ".html"));
        }
        return detail;
    }

    public SkuAlignRunAcceptedVO enqueueRun(SkuAlignRunRequestDTO req) {
        if (req == null || StringUtils.isBlank(req.getShopName())) {
            throw new CustomException("shopName required");
        }
        if (req.getScopeIds() == null || req.getScopeIds().isEmpty()) {
            throw new CustomException("scopeIds required");
        }
        AlignmentTriggerType trigger = req.getTriggerType() != null
                ? req.getTriggerType()
                : AlignmentTriggerType.MANUAL_REFRESH;

        AlignmentRun run = new AlignmentRun()
                .setShopName(req.getShopName())
                .setTriggerType(trigger)
                .setScopeType(req.getScopeType() != null ? req.getScopeType() : "PRODUCT")
                .setScopeIdsJson(AlignmentRunRepository.scopeIdsJson(req.getScopeIds()))
                .setEngineVersion("rule-v1");

        long runId = alignmentRunRepository.insertQueued(run);
        skuAlignEngineService.executeAsync(runId, req);

        return new SkuAlignRunAcceptedVO()
                .setRunId(runId)
                .setAccepted(true)
                .setEstimatedScopeCount(req.getScopeIds().size());
    }

    public SkuAlignRunStatusVO runStatus(String shopName, long runId) {
        AlignmentRun run = alignmentRunRepository.findById(runId, shopName)
                .orElseThrow(() -> new CustomException("RUN_NOT_FOUND"));
        return new SkuAlignRunStatusVO()
                .setRunId(run.getId())
                .setRunStatus(run.getRunStatus().name())
                .setMatchedCount(run.getMatchedCount())
                .setSuggestedCount(run.getSuggestedCount())
                .setUnmappedCount(run.getUnmappedCount())
                .setNoSourceCount(run.getNoSourceCount())
                .setBlockedCount(run.getBlockedCount())
                .setFailedCount(run.getFailedCount())
                .setErrorSummary(run.getErrorSummary());
    }

    /**
     * Step 3 — page enter: silently enqueue stale unresolved products (10min guard).
     */
    public SkuAlignRunAcceptedVO triggerPageEnter(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("shopName required");
        }
        List<String> stale = listStaleUnresolvedProductIds(shopName);
        if (stale.isEmpty()) {
            return new SkuAlignRunAcceptedVO().setAccepted(false).setEstimatedScopeCount(0);
        }
        return enqueueRun(new SkuAlignRunRequestDTO()
                .setShopName(shopName)
                .setTriggerType(AlignmentTriggerType.PAGE_ENTER)
                .setScopeType("PRODUCT_BATCH")
                .setScopeIds(stale));
    }

    /**
     * Step 3 — card expand: enqueue a single product when unresolved and stale.
     */
    public SkuAlignRunAcceptedVO triggerCardExpand(String shopName, String productId) {
        if (StringUtils.isAnyBlank(shopName, productId)) {
            throw new CustomException("shopName and productId required");
        }
        SkuProductOverviewVO product = findProduct(shopName, productId);
        if (!productHasUnresolved(shopName, product)) {
            return new SkuAlignRunAcceptedVO().setAccepted(false).setEstimatedScopeCount(0);
        }
        if (!isStaleForPageEnter(shopName, productId) || hasActiveRunForProduct(shopName, productId)) {
            return new SkuAlignRunAcceptedVO().setAccepted(false).setEstimatedScopeCount(0);
        }
        return enqueueRun(new SkuAlignRunRequestDTO()
                .setShopName(shopName)
                .setTriggerType(AlignmentTriggerType.CARD_EXPAND)
                .setScopeType("PRODUCT")
                .setScopeIds(List.of(productId)));
    }

    /**
     * Called after product-level bind confirm. Always async.
     */
    public void onProductBindConfirmed(String shopName, String productId, String primarySourceType) {
        SkuAlignRunRequestDTO req = new SkuAlignRunRequestDTO()
                .setShopName(shopName)
                .setTriggerType(AlignmentTriggerType.PRODUCT_BIND_CONFIRMED)
                .setScopeType("PRODUCT")
                .setScopeIds(List.of(productId));
        enqueueRun(req);
        log.info("SKU align V1 queued after product bind shop={} product={} source={}",
                shopName, productId, primarySourceType);
    }

    /**
     * Page enter: silent refresh if stale and unresolved exist.
     */
    public void maybeRefreshOnPageEnter(String shopName, List<String> productIdsWithUnresolved) {
        if (productIdsWithUnresolved == null || productIdsWithUnresolved.isEmpty()) {
            return;
        }
        List<String> stale = new ArrayList<>();
        for (String productId : productIdsWithUnresolved) {
            if (isStaleForPageEnter(shopName, productId) && !hasActiveRunForProduct(shopName, productId)) {
                stale.add(productId);
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        SkuAlignRunRequestDTO req = new SkuAlignRunRequestDTO()
                .setShopName(shopName)
                .setTriggerType(AlignmentTriggerType.PAGE_ENTER)
                .setScopeType("PRODUCT_BATCH")
                .setScopeIds(stale);
        enqueueRun(req);
    }

    public SkuAlignConfirmResultVO confirmSuggestions(SkuAlignConfirmSuggestionsDTO dto) {
        return skuAlignEngineService.confirmSuggestions(dto);
    }

    public void manualBind(SkuAlignManualBindDTO dto) {
        skuAlignEngineService.manualBind(dto);
    }

    public void blockVariant(SkuAlignBlockVariantDTO dto) {
        skuAlignEngineService.blockVariant(dto);
    }

    public SkuAlignRunAcceptedVO addSupplementSource(String productId, SkuAlignSupplementSourceDTO dto) {
        skuAlignEngineService.registerSupplementSource(productId, dto);
        return enqueueRun(new SkuAlignRunRequestDTO()
                .setShopName(dto.getShopName())
                .setTriggerType(AlignmentTriggerType.ADD_SUPPLEMENT_SOURCE)
                .setScopeType("PRODUCT")
                .setScopeIds(List.of(productId)));
    }

    public void recordAlias(SkuAlignAliasKnowledgeDTO dto) {
        skuAlignEngineService.recordAlias(dto);
    }

    private boolean isStaleForPageEnter(String shopName, String productId) {
        return alignmentRunRepository.findLatestForProduct(shopName, productId)
                .map(r -> r.getFinishedAt() != null
                        && Duration.between(r.getFinishedAt(), Instant.now()).compareTo(PAGE_ENTER_STALE) > 0)
                .orElse(true);
    }

    private boolean hasActiveRunForProduct(String shopName, String productId) {
        return alignmentRunRepository.findLatestForProduct(shopName, productId)
                .map(r -> r.getRunStatus() == AlignmentRunStatus.QUEUED
                        || r.getRunStatus() == AlignmentRunStatus.RUNNING)
                .orElse(false);
    }

    private List<String> listStaleUnresolvedProductIds(String shopName) {
        List<String> out = new ArrayList<>();
        for (SkuProductOverviewVO p : skuBindingOverviewService.overview(shopName)) {
            if (productHasUnresolved(shopName, p)
                    && isStaleForPageEnter(shopName, p.getThirdPlatformItemId())
                    && !hasActiveRunForProduct(shopName, p.getThirdPlatformItemId())) {
                out.add(p.getThirdPlatformItemId());
            }
        }
        return out;
    }

    private SkuProductOverviewVO findProduct(String shopName, String productId) {
        return skuBindingOverviewService.overview(shopName).stream()
                .filter(p -> productId.equals(p.getThirdPlatformItemId()))
                .findFirst()
                .orElseThrow(() -> new CustomException("PRODUCT_NOT_FOUND: 未找到该商品"));
    }

    private boolean productHasUnresolved(String shopName, SkuProductOverviewVO p) {
        Map<String, VariantAlignmentReview> reviews =
                reviewRepository.mapByProduct(shopName, p.getThirdPlatformItemId());
        if (p.getVariants() != null && !reviews.isEmpty()) {
            for (SkuVariantVO v : p.getVariants()) {
                VariantAlignmentReview review = reviews.get(v.getThirdPlatformSkuId());
                if (review == null || review.getReviewState() != VariantReviewState.RESOLVED) {
                    return true;
                }
            }
            return false;
        }
        return countUnresolved(p) > 0 || countPending(p) > 0;
    }

    private static int countPending(SkuProductOverviewVO p) {
        if (p.getVariants() == null) {
            return 0;
        }
        int pending = 0;
        for (SkuVariantVO v : p.getVariants()) {
            if (v.getBound() != null
                    && "PENDING".equalsIgnoreCase(String.valueOf(v.getBound().getBindStatus()))) {
                pending++;
            }
        }
        return pending;
    }

    private static int countUnresolved(SkuProductOverviewVO p) {
        if (p.getVariants() == null) {
            return 0;
        }
        int total = p.getVariants().size();
        int bound = 0;
        for (SkuVariantVO v : p.getVariants()) {
            if (v.getBound() != null) {
                bound++;
            }
        }
        return Math.max(0, total - bound);
    }

    private static SkuAlignProductSummaryVO mapProductSummary(SkuProductOverviewVO p,
                                                            Map<String, VariantAlignmentReview> reviews,
                                                            ProductSourceBinding psb) {
        int total = p.getVariants() != null ? p.getVariants().size() : 0;
        if (reviews != null && !reviews.isEmpty()) {
            int suggested = 0;
            int unmapped = 0;
            int noSource = 0;
            int blocked = 0;
            int aligned = 0;
            for (VariantAlignmentReview r : reviews.values()) {
                switch (r.getReviewState()) {
                    case SUGGESTED -> suggested++;
                    case UNMAPPED -> unmapped++;
                    case NO_SOURCE -> noSource++;
                    case RESOLVED -> aligned++;
                }
            }
            return new SkuAlignProductSummaryVO()
                    .setThirdPlatformItemId(p.getThirdPlatformItemId())
                    .setTitle(p.getTitle())
                    .setImageUrl(p.getImageUrl())
                    .setPrimaryOfferId(p.getTangbuyProductId())
                    .setTotalVariants(total)
                    .setAlignedVariants(aligned)
                    .setSuggestedVariants(suggested)
                    .setUnmappedVariants(unmapped)
                    .setNoSourceVariants(noSource)
                    .setBlockedVariants(blocked)
                    .setHasMultiSource(psb != null
                            && SkuOfferScopeHelper.hasSupplementOffer(psb.getSupplementalOfferIdsJson()))
                    .setLastAlignedAt(psb != null && psb.getLastAlignedAt() != null
                            ? psb.getLastAlignedAt().toString() : null);
        }
        return mapProductSummaryLegacy(p);
    }

    private static SkuAlignProductSummaryVO mapProductSummaryLegacy(SkuProductOverviewVO p) {
        int total = p.getVariants() != null ? p.getVariants().size() : 0;
        int aligned = 0;
        int suggested = 0;
        if (p.getVariants() != null) {
            for (SkuVariantVO v : p.getVariants()) {
                if (v.getBound() == null) {
                    continue;
                }
                aligned++;
                if ("PENDING".equalsIgnoreCase(String.valueOf(v.getBound().getBindStatus()))) {
                    suggested++;
                }
            }
        }
        return new SkuAlignProductSummaryVO()
                .setThirdPlatformItemId(p.getThirdPlatformItemId())
                .setTitle(p.getTitle())
                .setImageUrl(p.getImageUrl())
                .setPrimaryOfferId(p.getTangbuyProductId())
                .setTotalVariants(total)
                .setAlignedVariants(aligned)
                .setSuggestedVariants(suggested)
                .setUnmappedVariants(Math.max(0, total - aligned))
                .setNoSourceVariants(0)
                .setBlockedVariants(0)
                .setHasMultiSource(false);
    }

    private static SkuAlignVariantRowVO mapVariantRow(SkuVariantVO v,
                                                      VariantAlignmentReview review,
                                                      VariantSkuBinding v1Binding,
                                                      ProductSourceBinding psb) {
        if (review != null) {
            boolean pendingLegacy = v.getBound() != null
                    && "PENDING".equalsIgnoreCase(String.valueOf(v.getBound().getBindStatus()));
            boolean hasSupplement = psb != null
                    && SkuOfferScopeHelper.hasSupplementOffer(psb.getSupplementalOfferIdsJson());
            SkuAlignVariantRowVO row = new SkuAlignVariantRowVO()
                    .setThirdPlatformSkuId(v.getThirdPlatformSkuId())
                    .setOptionText(v.getOptionLabel())
                    .setShopifyImage(v.getImageUrl())
                    .setSalePrice(v.getPrice() != null ? v.getPrice().toPlainString() : null)
                    .setReviewState(review.getReviewState().name())
                    .setReasonText(review.getReasonText())
                    .setDisplayStatus("READY")
                    .setActions(new SkuAlignVariantActionsVO()
                            .setCanConfirm(review.getReviewState() == VariantReviewState.SUGGESTED || pendingLegacy)
                            .setCanReselect(true)
                            .setCanAddSupplementSource(
                                    review.getReviewState() == VariantReviewState.NO_SOURCE && !hasSupplement)
                            .setCanBlock(review.getReviewState() != VariantReviewState.RESOLVED));
            if (review.getSuggestedOfferId() != null) {
                row.setSuggestedCandidate(new SkuAlignSuggestedCandidateVO()
                        .setOfferId(review.getSuggestedOfferId())
                        .setOfferSkuId(review.getSuggestedOfferSkuId())
                        .setConfidenceLevel(review.getConfidenceLevel() != null
                                ? review.getConfidenceLevel().name() : null)
                        .setScore(review.getScore() != null ? review.getScore().doubleValue() : null));
            }
            SkuAlignCurrentBindingVO bindingVo = null;
            if (v1Binding != null) {
                bindingVo = new SkuAlignCurrentBindingVO()
                        .setOfferId(v1Binding.getOfferId())
                        .setOfferSkuId(v1Binding.getOfferSkuId())
                        .setBindingState(v1Binding.getBindingState() != null
                                ? v1Binding.getBindingState().name() : null)
                        .setMatchSource(v1Binding.getMatchSource() != null
                                ? v1Binding.getMatchSource().name() : null)
                        .setConfidenceLevel(v1Binding.getConfidenceLevel() != null
                                ? v1Binding.getConfidenceLevel().name() : null)
                        .setManualLocked(v1Binding.isManualLocked());
            } else if (v.getBound() != null) {
                bindingVo = new SkuAlignCurrentBindingVO()
                        .setOfferId(v.getBound().getTangbuyProductId())
                        .setOfferSkuId(v.getBound().getTangbuySkuId())
                        .setMatchSource(v.getBound().getMatchSource())
                        .setManualLocked("MANUAL".equalsIgnoreCase(v.getBound().getMatchSource()));
            }
            row.setCurrentBinding(bindingVo);
            return row;
        }
        return mapVariantRowLegacy(v);
    }

    private static SkuAlignVariantRowVO mapVariantRowLegacy(SkuVariantVO v) {
        boolean bound = v.getBound() != null;
        boolean pending = bound && "PENDING".equalsIgnoreCase(String.valueOf(v.getBound().getBindStatus()));
        SkuAlignVariantRowVO row = new SkuAlignVariantRowVO()
                .setThirdPlatformSkuId(v.getThirdPlatformSkuId())
                .setOptionText(v.getOptionLabel())
                .setShopifyImage(v.getImageUrl())
                .setSalePrice(v.getPrice() != null ? v.getPrice().toPlainString() : null)
                .setReviewState(pending ? "SUGGESTED" : bound ? "RESOLVED" : "UNMAPPED")
                .setDisplayStatus(bound ? "READY" : "LOADING")
                .setActions(new SkuAlignVariantActionsVO()
                        .setCanConfirm(pending)
                        .setCanReselect(true)
                        .setCanAddSupplementSource(false)
                        .setCanBlock(!bound));
        if (bound && v.getBound() != null) {
            row.setCurrentBinding(new SkuAlignCurrentBindingVO()
                    .setOfferId(v.getBound().getTangbuyProductId())
                    .setOfferSkuId(v.getBound().getTangbuySkuId())
                    .setMatchSource(v.getBound().getMatchSource())
                    .setManualLocked("MANUAL".equalsIgnoreCase(v.getBound().getMatchSource())));
        }
        return row;
    }
}
