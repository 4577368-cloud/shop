package com.tang.plugin.service.skualign;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.TxManger;
import com.tang.plugin.domain.dto.match.SkuBindDTO;
import com.tang.plugin.domain.dto.match.sku.OfferDetailVO;
import com.tang.plugin.domain.dto.skualign.*;
import com.tang.plugin.domain.entity.match.ShopProductBinding;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import com.tang.plugin.domain.entity.skualign.AlignmentRun;
import com.tang.plugin.domain.entity.skualign.ProductSourceBinding;
import com.tang.plugin.domain.entity.skualign.VariantAlignmentReview;
import com.tang.plugin.domain.entity.skualign.VariantSkuBinding;
import com.tang.plugin.enums.match.BindingStatus;
import com.tang.plugin.enums.match.MatchSource;
import com.tang.plugin.enums.skualign.AlignmentRunStatus;
import com.tang.plugin.enums.skualign.AlignmentTriggerType;
import com.tang.plugin.enums.skualign.ProductOrigin;
import com.tang.plugin.enums.skualign.SourceRole;
import com.tang.plugin.enums.skualign.VariantBindingState;
import com.tang.plugin.enums.skualign.VariantReviewState;
import com.tang.plugin.repository.ShopProductBindingRepository;
import com.tang.plugin.repository.ThirdPlatformSkuRepository;
import com.tang.plugin.repository.skualign.AlignmentCandidateRepository;
import com.tang.plugin.repository.skualign.AlignmentRunRepository;
import com.tang.plugin.repository.skualign.ProductSourceBindingRepository;
import com.tang.plugin.repository.skualign.ShopSkuAliasKnowledgeRepository;
import com.tang.plugin.repository.skualign.VariantAlignmentReviewRepository;
import com.tang.plugin.enums.skualign.ReviewReasonCode;
import com.tang.plugin.repository.skualign.VariantSkuBindingRepository;
import com.tang.plugin.service.match.sku.Crossborder1688ProductClient;
import com.tang.plugin.service.match.sku.OfferSkuMatrixValidator;
import com.tang.plugin.service.match.sku.SkuAutoAlignService;
import com.tang.plugin.service.match.sku.SkuManualBindService;
import com.tang.plugin.service.match.sku.SkuMatcher;
import com.tang.plugin.service.match.sku.SkuMatcher.VariantAlignment;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * V1 alignment engine — Step 2: writes {@code variant_alignment_review}, optional
 * {@code variant_sku_binding}, and dual-writes legacy RULE bindings for matched variants.
 */
@Slf4j
@Service
public class SkuAlignEngineService {

    @Resource
    private AlignmentRunRepository alignmentRunRepository;
    @Resource
    private VariantAlignmentReviewRepository reviewRepository;
    @Resource
    private VariantSkuBindingRepository v1BindingRepository;
    @Resource
    private ProductSourceBindingRepository productSourceRepository;
    @Resource
    private AlignmentCandidateRepository candidateRepository;
    @Resource
    private ThirdPlatformSkuRepository thirdPlatformSkuRepository;
    @Resource
    private ShopProductBindingRepository shopProductBindingRepository;
    @Resource
    private Crossborder1688ProductClient crossborder1688ProductClient;
    @Resource
    private SkuAutoAlignService skuAutoAlignService;
    @Resource
    private SkuManualBindService skuManualBindService;
    @Resource
    private OfferSkuMatrixValidator offerSkuMatrixValidator;
    @Resource
    private ShopSkuAliasKnowledgeRepository aliasKnowledgeRepository;
    @Resource
    private ProductPrimaryOfferResolver productPrimaryOfferResolver;
    @Resource
    private TxManger txManger;

    @Async
    public void executeAsync(long runId, SkuAlignRunRequestDTO req) {
        alignmentRunRepository.markRunning(runId);
        AlignmentRun stats = new AlignmentRun()
                .setMatchedCount(0)
                .setSuggestedCount(0)
                .setUnmappedCount(0)
                .setNoSourceCount(0)
                .setBlockedCount(0)
                .setFailedCount(0);
        int failedProducts = 0;
        try {
            for (String productId : req.getScopeIds()) {
                try {
                    alignProduct(runId, req.getShopName(), productId, stats, req);
                } catch (Exception e) {
                    failedProducts++;
                    log.warn("SKU align V1 product failed runId={} product={} err={}",
                            runId, productId, e.getMessage());
                }
            }
            stats.setFailedCount(failedProducts);
            AlignmentRunStatus status = failedProducts > 0 && failedProducts < req.getScopeIds().size()
                    ? AlignmentRunStatus.PARTIAL
                    : failedProducts == req.getScopeIds().size()
                    ? AlignmentRunStatus.FAILED
                    : AlignmentRunStatus.SUCCEEDED;
            alignmentRunRepository.finish(runId, status, stats);
        } catch (Exception e) {
            stats.setErrorSummary(e.getMessage());
            stats.setFailedCount(req.getScopeIds().size());
            alignmentRunRepository.finish(runId, AlignmentRunStatus.FAILED, stats);
        }
    }

    private void alignProduct(long runId,
                              String shopName,
                              String productId,
                              AlignmentRun stats,
                              SkuAlignRunRequestDTO req) {
        AlignmentTriggerType trigger = req.getTriggerType() != null
                ? req.getTriggerType()
                : AlignmentTriggerType.MANUAL_REFRESH;
        List<ThirdPlatformSku> variants = thirdPlatformSkuRepository.listByItem(shopName, productId);
        if (variants.isEmpty()) {
            throw new CustomException(SkuAutoAlignService.ERR_NO_VARIANT + ": 该商品无可用变体");
        }

        String primaryOfferId = productPrimaryOfferResolver.resolvePrimaryOffer(shopName, productId);
        OfferDetailVO primaryDetail = crossborder1688ProductClient.queryProductDetail(primaryOfferId, "en");
        if (primaryDetail.getSkus() == null || primaryDetail.getSkus().isEmpty()) {
            throw new CustomException(SkuAutoAlignService.ERR_NO_OFFER_SKU + ": 货源未返回 SKU 矩阵");
        }

        ProductSourceBinding productSource = productSourceRepository.findByProduct(shopName, productId).orElse(null);
        String supplementOfferId = SkuOfferScopeHelper.parseSupplementOfferId(
                productSource != null ? productSource.getSupplementalOfferIdsJson() : null);
        OfferDetailVO supplementDetail = null;
        List<VariantAlignment> supplementAlignments = List.of();
        if (StringUtils.isNotBlank(supplementOfferId)) {
            supplementDetail = crossborder1688ProductClient.queryProductDetail(supplementOfferId, "en");
            if (supplementDetail.getSkus() == null || supplementDetail.getSkus().isEmpty()) {
                throw new CustomException(SkuAutoAlignService.ERR_NO_OFFER_SKU
                        + ": 补充货源未返回 SKU 矩阵");
            }
            supplementAlignments = SkuMatcher.align(variants, supplementDetail.getSkus());
        }

        List<VariantAlignment> primaryAlignments = SkuMatcher.align(variants, primaryDetail.getSkus());
        Map<String, VariantSkuBinding> existingBindings = v1BindingRepository.mapActiveByProduct(shopName, productId);
        Map<String, VariantAlignmentReview> existingReviews =
                reviewRepository.mapByProduct(shopName, productId);
        String offerScopeJson = SkuOfferScopeHelper.buildScopeJson(primaryOfferId, supplementOfferId);
        boolean internalOrigin = resolveInternalOrigin(shopName, productId);
        List<VariantAlignment> legacyPendingPrimary = new ArrayList<>();
        List<VariantAlignment> legacyPendingSupplement = new ArrayList<>();
        List<VariantAlignment> legacyActivePrimary = new ArrayList<>();
        List<VariantAlignment> legacyActiveSupplement = new ArrayList<>();

        int[] matched = {0};
        int[] suggested = {0};
        int[] unmapped = {0};
        int[] noSource = {0};
        int[] blocked = {0};

        final OfferDetailVO supplementDetailRef = supplementDetail;
        final List<VariantAlignment> supplementAlignmentsRef = supplementAlignments;
        final AlignmentTriggerType triggerType = trigger;

        txManger.run(() -> {
            for (ThirdPlatformSku variant : variants) {
                final ThirdPlatformSku variantRef = variant;
                VariantAlignment primaryAlignment = primaryAlignments.stream()
                        .filter(a -> variantRef.getThirdPlatformSkuId().equals(a.variantGid()))
                        .findFirst()
                        .orElse(new VariantAlignment(variantRef.getThirdPlatformSkuId(),
                                variantRef.getOption1(), null, null, 0d, false));

                VariantSkuBinding existing = existingBindings.get(variantRef.getThirdPlatformSkuId());
                VariantAlignmentReview priorReview =
                        existingReviews.get(variantRef.getThirdPlatformSkuId());

                if (triggerType == AlignmentTriggerType.ADD_SUPPLEMENT_SOURCE
                        && isPrimaryResolved(existing, priorReview)) {
                    incrementResolvedStats(existing, matched, blocked);
                    continue;
                }

                SkuReviewClassifier.Classification resolved = SkuReviewClassifier.classify(
                        variantRef, primaryDetail.getSkus(), primaryAlignment, primaryOfferId,
                        internalOrigin, existing);

                if (shouldTrySupplement(resolved, supplementOfferId, supplementDetailRef)) {
                    VariantAlignment supplementAlignment = supplementAlignmentsRef.stream()
                            .filter(a -> variantRef.getThirdPlatformSkuId().equals(a.variantGid()))
                            .findFirst()
                            .orElse(null);
                    SkuReviewClassifier.Classification supplementClass =
                            SkuReviewClassifier.classifySupplementOffer(
                                    variantRef,
                                    supplementDetailRef.getSkus(),
                                    supplementAlignment,
                                    supplementOfferId,
                                    internalOrigin,
                                    existing);
                    if (supplementClass != null) {
                        resolved = supplementClass;
                    }
                }
                final SkuReviewClassifier.Classification c = resolved;

                candidateRepository.deleteByRunAndVariant(runId, variantRef.getThirdPlatformSkuId());

                VariantAlignmentReview review = new VariantAlignmentReview()
                        .setShopName(shopName)
                        .setThirdPlatformItemId(productId)
                        .setThirdPlatformSkuId(variantRef.getThirdPlatformSkuId())
                        .setCurrentOfferScopeJson(offerScopeJson)
                        .setReviewState(c.reviewState())
                        .setSuggestedOfferId(c.suggestedOfferId())
                        .setSuggestedOfferSkuId(c.suggestedOfferSkuId())
                        .setSuggestedSourceRole(c.suggestedOfferId() != null
                                ? c.effectiveSourceRole() : null)
                        .setSuggestedMatchSource(c.suggestedMatchSource())
                        .setScore(c.score())
                        .setConfidenceLevel(c.confidenceLevel())
                        .setReasonCode(c.reasonCode().name())
                        .setReasonText(c.reasonText())
                        .setRequiresUserAction(c.requiresUserAction())
                        .setLastRunId(runId);
                reviewRepository.upsert(review);

                if (c.suggestedOfferId() != null && c.suggestedOfferSkuId() != null) {
                    candidateRepository.insert(
                            runId,
                            variantRef.getThirdPlatformSkuId(),
                            1,
                            c.suggestedOfferId(),
                            c.suggestedOfferSkuId(),
                            c.suggestedMatchSource() != null ? c.suggestedMatchSource() : MatchSource.RULE.name(),
                            c.score(),
                            c.confidenceLevel(),
                            c.reasonText(),
                            c.reviewState() == VariantReviewState.SUGGESTED);
                }

                if (c.writeV1ActiveBinding()) {
                    String bindingOfferId = c.suggestedOfferId() != null ? c.suggestedOfferId() : primaryOfferId;
                    String bindingSkuId = c.suggestedOfferSkuId() != null
                            ? c.suggestedOfferSkuId() : primaryAlignment.skuId();
                    VariantSkuBinding binding = new VariantSkuBinding()
                            .setShopName(shopName)
                            .setThirdPlatformItemId(productId)
                            .setThirdPlatformSkuId(variantRef.getThirdPlatformSkuId())
                            .setOfferId(bindingOfferId)
                            .setOfferSkuId(bindingSkuId)
                            .setSourceRole(c.effectiveSourceRole())
                            .setMatchSource(MatchSource.RULE)
                            .setBindingState(c.effectiveBindingState())
                            .setConfidenceScore(c.score())
                            .setConfidenceLevel(c.confidenceLevel())
                            .setManualLocked(false)
                            .setActive(true)
                            .setCreatedByType("SYSTEM");
                    v1BindingRepository.upsertActive(binding);
                }

                if (c.writeLegacyPending()) {
                    VariantAlignment legacyAlignment = c.effectiveSourceRole() == SourceRole.SUPPLEMENT
                            ? supplementAlignmentsRef.stream()
                            .filter(a -> variantRef.getThirdPlatformSkuId().equals(a.variantGid()))
                            .findFirst().orElse(null)
                            : primaryAlignment;
                    if (legacyAlignment != null && legacyAlignment.matched()) {
                        if (c.effectiveSourceRole() == SourceRole.SUPPLEMENT) {
                            legacyPendingSupplement.add(legacyAlignment);
                        } else {
                            legacyPendingPrimary.add(legacyAlignment);
                        }
                    }
                } else if (c.writeV1ActiveBinding()) {
                    VariantAlignment legacyAlignment = c.effectiveSourceRole() == SourceRole.SUPPLEMENT
                            ? supplementAlignmentsRef.stream()
                            .filter(a -> variantRef.getThirdPlatformSkuId().equals(a.variantGid()))
                            .findFirst().orElse(null)
                            : primaryAlignment;
                    if (legacyAlignment != null && legacyAlignment.matched()) {
                        if (c.effectiveSourceRole() == SourceRole.SUPPLEMENT) {
                            legacyActiveSupplement.add(legacyAlignment);
                        } else {
                            legacyActivePrimary.add(legacyAlignment);
                        }
                    }
                }

                switch (c.reviewState()) {
                    case SUGGESTED -> suggested[0]++;
                    case UNMAPPED -> unmapped[0]++;
                    case NO_SOURCE -> noSource[0]++;
                    case RESOLVED -> {
                        if (existing != null && SkuAlignProtectionRules.isBlockedBinding(existing)) {
                            blocked[0]++;
                        } else if (c.writeV1ActiveBinding() || (existing != null && existing.getOfferSkuId() != null)) {
                            matched[0]++;
                        } else if (!c.requiresUserAction()) {
                            matched[0]++;
                        }
                    }
                }
            }
        });

        String primaryDetailUrl = "https://detail.1688.com/offer/" + primaryOfferId + ".html";
        if (!legacyPendingPrimary.isEmpty()) {
            skuAutoAlignService.persistRuleMatches(
                    shopName, productId, primaryOfferId, legacyPendingPrimary, primaryDetailUrl);
        }
        if (!legacyActivePrimary.isEmpty()) {
            skuAutoAlignService.persistActiveRuleMatches(
                    shopName, productId, primaryOfferId, legacyActivePrimary, primaryDetailUrl);
        }
        if (!legacyPendingSupplement.isEmpty() && StringUtils.isNotBlank(supplementOfferId)) {
            String supplementDetailUrl = "https://detail.1688.com/offer/" + supplementOfferId + ".html";
            skuAutoAlignService.persistRuleMatches(
                    shopName, productId, supplementOfferId, legacyPendingSupplement, supplementDetailUrl);
        }
        if (!legacyActiveSupplement.isEmpty() && StringUtils.isNotBlank(supplementOfferId)) {
            String supplementDetailUrl = "https://detail.1688.com/offer/" + supplementOfferId + ".html";
            skuAutoAlignService.persistActiveRuleMatches(
                    shopName, productId, supplementOfferId, legacyActiveSupplement, supplementDetailUrl);
        }

        stats.setMatchedCount(stats.getMatchedCount() + matched[0]);
        stats.setSuggestedCount(stats.getSuggestedCount() + suggested[0]);
        stats.setUnmappedCount(stats.getUnmappedCount() + unmapped[0]);
        stats.setNoSourceCount(stats.getNoSourceCount() + noSource[0]);
        stats.setBlockedCount(stats.getBlockedCount() + blocked[0]);

        int unresolved = suggested[0] + unmapped[0] + noSource[0];
        productSourceRepository.upsertSummary(new ProductSourceBinding()
                .setShopName(shopName)
                .setThirdPlatformItemId(productId)
                .setPrimaryOfferId(primaryOfferId)
                .setPrimarySourceType("IMAGE")
                .setProductOrigin(internalOrigin ? ProductOrigin.INTERNAL : ProductOrigin.EXTERNAL)
                .setMatchedVariantsCount(matched[0])
                .setTotalVariantsCount(variants.size())
                .setUnresolvedVariantsCount(unresolved)
                .setLastAlignmentRunId(runId)
                .setLastAlignedAt(Instant.now()));

        log.info("SkuAlignV1 runId={} shop={} product={} trigger={} matched={} suggested={} unmapped={} noSource={} supplement={}",
                runId, shopName, productId, trigger, matched[0], suggested[0], unmapped[0], noSource[0],
                supplementOfferId);
    }

    private static boolean shouldTrySupplement(SkuReviewClassifier.Classification primary,
                                               String supplementOfferId,
                                               OfferDetailVO supplementDetail) {
        if (StringUtils.isBlank(supplementOfferId) || supplementDetail == null) {
            return false;
        }
        return primary.reviewState() == VariantReviewState.NO_SOURCE
                || primary.reviewState() == VariantReviewState.UNMAPPED;
    }

    private static boolean isPrimaryResolved(VariantSkuBinding existing, VariantAlignmentReview priorReview) {
        if (existing != null
                && existing.isActive()
                && existing.getSourceRole() == SourceRole.PRIMARY
                && existing.getBindingState() == VariantBindingState.ALIGNED
                && StringUtils.isNotBlank(existing.getOfferSkuId())) {
            return true;
        }
        return priorReview != null && priorReview.getReviewState() == VariantReviewState.RESOLVED;
    }

    private static void incrementResolvedStats(VariantSkuBinding existing, int[] matched, int[] blocked) {
        if (existing != null && SkuAlignProtectionRules.isBlockedBinding(existing)) {
            blocked[0]++;
        } else {
            matched[0]++;
        }
    }

    public void registerSupplementSource(String productId, SkuAlignSupplementSourceDTO dto) {
        if (dto == null || StringUtils.isBlank(dto.getShopName())) {
            throw new CustomException("supplement-source requires shopName");
        }
        if (StringUtils.isAnyBlank(productId, dto.getOfferId())) {
            throw new CustomException("supplement-source requires productId and offerId");
        }
        String shopName = dto.getShopName().trim();
        String supplementOfferId = dto.getOfferId().trim();
        String primaryOfferId = productPrimaryOfferResolver.resolvePrimaryOffer(shopName, productId);
        if (primaryOfferId.equals(supplementOfferId)) {
            throw new CustomException("SUPPLEMENT_SAME_AS_PRIMARY: 补充货源不能与主货源相同");
        }

        ProductSourceBinding existing = productSourceRepository.findByProduct(shopName, productId).orElse(null);
        if (SkuOfferScopeHelper.hasSupplementOffer(
                existing != null ? existing.getSupplementalOfferIdsJson() : null)) {
            throw new CustomException("SUPPLEMENT_LIMIT: V1 每个商品仅支持 1 个补充货源");
        }

        OfferDetailVO supplementDetail = crossborder1688ProductClient.queryProductDetail(supplementOfferId, "en");
        if (supplementDetail.getSkus() == null || supplementDetail.getSkus().isEmpty()) {
            throw new CustomException(SkuAutoAlignService.ERR_NO_OFFER_SKU + ": 补充货源未返回 SKU 矩阵");
        }

        Map<String, VariantAlignmentReview> reviews = reviewRepository.mapByProduct(shopName, productId);
        boolean hasUnresolved = reviews.values().stream().anyMatch(r ->
                r.getReviewState() == VariantReviewState.NO_SOURCE
                        || r.getReviewState() == VariantReviewState.UNMAPPED);
        if (!reviews.isEmpty() && !hasUnresolved) {
            throw new CustomException("NO_UNRESOLVED_VARIANT: 当前商品无 no_source/unmapped 变体需要补充货源");
        }

        boolean internalOrigin = resolveInternalOrigin(shopName, productId);
        productSourceRepository.setSupplementOffer(
                shopName,
                productId,
                primaryOfferId,
                "IMAGE",
                internalOrigin ? ProductOrigin.INTERNAL : ProductOrigin.EXTERNAL,
                SkuOfferScopeHelper.buildSupplementJson(supplementOfferId));

        log.info("SkuAlignV1 supplement registered shop={} product={} primary={} supplement={}",
                shopName, productId, primaryOfferId, supplementOfferId);
    }

    private int confirmLegacyPendingInScope(SkuAlignConfirmSuggestionsDTO dto) {
        List<String> variantIds = resolveVariantIdsInScope(dto);
        int confirmed = 0;
        for (String variantId : variantIds) {
            if (StringUtils.isBlank(variantId)) {
                continue;
            }
            Optional<ShopProductBinding> legacy =
                    shopProductBindingRepository.findBindableBySkuId(dto.getShopName(), variantId.trim());
            if (legacy.isEmpty() || legacy.get().getBindStatus() != BindingStatus.PENDING) {
                continue;
            }
            Optional<VariantAlignmentReview> review =
                    reviewRepository.findByVariant(dto.getShopName(), variantId.trim());
            if (review.isPresent() && review.get().getReviewState() == VariantReviewState.SUGGESTED) {
                continue;
            }
            skuAutoAlignService.acknowledge(dto.getShopName(), variantId.trim());
            if (review.isPresent()) {
                VariantAlignmentReview row = review.get();
                row.setReviewState(VariantReviewState.RESOLVED)
                        .setRequiresUserAction(false)
                        .setReasonText("用户已确认 legacy 待确认绑定");
                reviewRepository.upsert(row);
            }
            confirmed++;
        }
        return confirmed;
    }

    private List<String> resolveVariantIdsInScope(SkuAlignConfirmSuggestionsDTO dto) {
        String scope = dto.getTargetScope().trim().toUpperCase();
        List<String> out = new ArrayList<>();
        switch (scope) {
            case "PAGE" -> {
                Map<String, Map<String, VariantAlignmentReview>> byShop =
                        reviewRepository.mapByShop(dto.getShopName());
                for (Map<String, VariantAlignmentReview> byVariant : byShop.values()) {
                    out.addAll(byVariant.keySet());
                }
                for (ShopProductBinding b : shopProductBindingRepository.listBindableByShop(dto.getShopName())) {
                    if (StringUtils.isNotBlank(b.getThirdPlatformSkuId())) {
                        out.add(b.getThirdPlatformSkuId());
                    }
                }
            }
            case "PRODUCT" -> {
                if (dto.getProductIds() == null) {
                    break;
                }
                for (String productId : dto.getProductIds()) {
                    if (StringUtils.isBlank(productId)) {
                        continue;
                    }
                    reviewRepository.mapByProduct(dto.getShopName(), productId.trim())
                            .keySet().forEach(out::add);
                    for (ShopProductBinding b : shopProductBindingRepository.listBindableByShop(dto.getShopName())) {
                        if (productId.equals(b.getThirdPlatformItemId())
                                && StringUtils.isNotBlank(b.getThirdPlatformSkuId())) {
                            out.add(b.getThirdPlatformSkuId());
                        }
                    }
                }
            }
            case "VARIANTS" -> {
                if (dto.getVariantIds() != null) {
                    out.addAll(dto.getVariantIds());
                }
            }
            default -> {
            }
        }
        return out.stream().filter(StringUtils::isNotBlank).map(String::trim).distinct().toList();
    }

    private String resolveBoundOffer(String shopName, String itemId) {
        return productPrimaryOfferResolver.resolvePrimaryOffer(shopName, itemId);
    }

    private boolean resolveInternalOrigin(String shopName, String productId) {
        return productSourceRepository.findByProduct(shopName, productId)
                .map(p -> p.getProductOrigin() == ProductOrigin.INTERNAL)
                .orElse(false);
    }

    public SkuAlignConfirmResultVO confirmSuggestions(SkuAlignConfirmSuggestionsDTO dto) {
        if (dto == null || StringUtils.isBlank(dto.getShopName())) {
            throw new CustomException("confirm-suggestions requires shopName");
        }
        if (StringUtils.isBlank(dto.getTargetScope())) {
            throw new CustomException("confirm-suggestions requires targetScope");
        }
        List<VariantAlignmentReview> targets = resolveConfirmTargets(dto);
        int confirmed = 0;
        for (VariantAlignmentReview review : targets) {
            if (confirmOneSuggestion(dto.getShopName(), review)) {
                confirmed++;
            }
        }
        confirmed += confirmLegacyPendingInScope(dto);
        if (confirmed == 0) {
            log.info("confirm-suggestions noop shop={} scope={}", dto.getShopName(), dto.getTargetScope());
        } else {
            log.info("confirm-suggestions shop={} scope={} confirmed={}",
                    dto.getShopName(), dto.getTargetScope(), confirmed);
        }
        return new SkuAlignConfirmResultVO().setConfirmedCount(confirmed);
    }

    public void manualBind(SkuAlignManualBindDTO dto) {
        if (dto == null || StringUtils.isBlank(dto.getShopName())) {
            throw new CustomException("manual bind requires shopName");
        }
        if (StringUtils.isAnyBlank(dto.getThirdPlatformItemId(), dto.getThirdPlatformSkuId(),
                dto.getOfferId(), dto.getOfferSkuId())) {
            throw new CustomException("manual bind requires product, variant, offerId and offerSkuId");
        }
        offerSkuMatrixValidator.assertSkuInOffer(dto.getOfferId(), dto.getOfferSkuId());
        String spec = offerSkuMatrixValidator.resolveSkuSpecLabel(dto.getOfferId(), dto.getOfferSkuId());
        String detailUrl = "https://detail.1688.com/offer/" + dto.getOfferId().trim() + ".html";
        SourceRole role = dto.getSourceRole() != null ? dto.getSourceRole() : SourceRole.PRIMARY;

        txManger.run(() -> {
            VariantSkuBinding binding = SkuAlignProtectionRules.manualBindTemplate(new VariantSkuBinding()
                    .setShopName(dto.getShopName())
                    .setThirdPlatformItemId(dto.getThirdPlatformItemId())
                    .setThirdPlatformSkuId(dto.getThirdPlatformSkuId())
                    .setOfferId(dto.getOfferId().trim())
                    .setOfferSkuId(dto.getOfferSkuId().trim())
                    .setSourceRole(role)
                    .setBindingState(VariantBindingState.ALIGNED)
                    .setConfidenceScore(java.math.BigDecimal.ONE)
                    .setConfidenceLevel(com.tang.plugin.enums.skualign.ConfidenceLevel.HIGH)
                    .setActive(true));
            v1BindingRepository.upsertActive(binding);

            VariantAlignmentReview review = reviewRepository
                    .findByVariant(dto.getShopName(), dto.getThirdPlatformSkuId())
                    .orElse(new VariantAlignmentReview()
                            .setShopName(dto.getShopName())
                            .setThirdPlatformItemId(dto.getThirdPlatformItemId())
                            .setThirdPlatformSkuId(dto.getThirdPlatformSkuId()));
            review.setReviewState(VariantReviewState.RESOLVED)
                    .setSuggestedOfferId(dto.getOfferId().trim())
                    .setSuggestedOfferSkuId(dto.getOfferSkuId().trim())
                    .setSuggestedSourceRole(role)
                    .setSuggestedMatchSource(MatchSource.MANUAL.name())
                    .setReasonCode(ReviewReasonCode.MANUAL_OVERRIDE.name())
                    .setReasonText(StringUtils.defaultIfBlank(dto.getReason(), "用户手动选择 SKU"))
                    .setRequiresUserAction(false);
            reviewRepository.upsert(review);
        });

        skuManualBindService.bind(new SkuBindDTO()
                .setShopName(dto.getShopName())
                .setThirdPlatformItemId(dto.getThirdPlatformItemId())
                .setThirdPlatformSkuId(dto.getThirdPlatformSkuId())
                .setTangbuyProductId(dto.getOfferId().trim())
                .setTangbuySkuId(dto.getOfferSkuId().trim())
                .setTangbuySkuSpec(spec)
                .setDetailUrl(detailUrl));

        maybeRecordManualAlias(dto.getShopName(), dto.getThirdPlatformSkuId(), spec);
        log.info("SkuAlignV1 manual bind shop={} variant={} offer={} sku={}",
                dto.getShopName(), dto.getThirdPlatformSkuId(), dto.getOfferId(), dto.getOfferSkuId());
    }

    public void blockVariant(SkuAlignBlockVariantDTO dto) {
        if (dto == null || StringUtils.isBlank(dto.getShopName())) {
            throw new CustomException("block requires shopName");
        }
        if (StringUtils.isAnyBlank(dto.getThirdPlatformItemId(), dto.getThirdPlatformSkuId())) {
            throw new CustomException("block requires product and variant");
        }
        String reasonCode = StringUtils.defaultIfBlank(dto.getReasonCode(), ReviewReasonCode.BLOCKED_BY_USER.name());
        String reasonText = StringUtils.defaultIfBlank(dto.getReasonText(), "用户标记该变体不可履约");

        txManger.run(() -> {
            VariantSkuBinding binding = SkuAlignProtectionRules.manualBindTemplate(new VariantSkuBinding()
                    .setShopName(dto.getShopName())
                    .setThirdPlatformItemId(dto.getThirdPlatformItemId())
                    .setThirdPlatformSkuId(dto.getThirdPlatformSkuId())
                    .setOfferId(null)
                    .setOfferSkuId(null)
                    .setSourceRole(SourceRole.PRIMARY)
                    .setBindingState(VariantBindingState.BLOCKED)
                    .setActive(true));
            v1BindingRepository.upsertActive(binding);

            VariantAlignmentReview review = reviewRepository
                    .findByVariant(dto.getShopName(), dto.getThirdPlatformSkuId())
                    .orElse(new VariantAlignmentReview()
                            .setShopName(dto.getShopName())
                            .setThirdPlatformItemId(dto.getThirdPlatformItemId())
                            .setThirdPlatformSkuId(dto.getThirdPlatformSkuId()));
            review.setReviewState(VariantReviewState.RESOLVED)
                    .setSuggestedOfferId(null)
                    .setSuggestedOfferSkuId(null)
                    .setReasonCode(reasonCode)
                    .setReasonText(reasonText)
                    .setRequiresUserAction(false);
            reviewRepository.upsert(review);

            shopProductBindingRepository.deactivateBySkuId(dto.getShopName(), dto.getThirdPlatformSkuId());
        });
        log.info("SkuAlignV1 block shop={} variant={}", dto.getShopName(), dto.getThirdPlatformSkuId());
    }

    public void recordAlias(SkuAlignAliasKnowledgeDTO dto) {
        if (dto == null || StringUtils.isBlank(dto.getShopName())) {
            throw new CustomException("alias requires shopName");
        }
        if (StringUtils.isAnyBlank(dto.getSourceText(), dto.getTargetText())) {
            throw new CustomException("alias requires sourceText and targetText");
        }
        aliasKnowledgeRepository.upsertAlias(dto);
    }

    private boolean confirmOneSuggestion(String shopName, VariantAlignmentReview review) {
        VariantSkuBinding existing = v1BindingRepository
                .findActiveByVariant(shopName, review.getThirdPlatformSkuId())
                .orElse(null);
        if (!SkuAlignProtectionRules.canConfirmSuggestion(existing, review.getReviewState())) {
            return false;
        }
        if (StringUtils.isAnyBlank(review.getSuggestedOfferId(), review.getSuggestedOfferSkuId())) {
            return false;
        }
        offerSkuMatrixValidator.assertSkuInOffer(
                review.getSuggestedOfferId(), review.getSuggestedOfferSkuId());

        MatchSource matchSource = parseMatchSource(review.getSuggestedMatchSource());
        txManger.run(() -> {
            VariantSkuBinding binding = new VariantSkuBinding()
                    .setShopName(shopName)
                    .setThirdPlatformItemId(review.getThirdPlatformItemId())
                    .setThirdPlatformSkuId(review.getThirdPlatformSkuId())
                    .setOfferId(review.getSuggestedOfferId())
                    .setOfferSkuId(review.getSuggestedOfferSkuId())
                    .setSourceRole(review.getSuggestedSourceRole() != null
                            ? review.getSuggestedSourceRole() : SourceRole.PRIMARY)
                    .setMatchSource(matchSource)
                    .setBindingState(review.getSuggestedSourceRole() == SourceRole.SUPPLEMENT
                            ? VariantBindingState.MULTI_SOURCE
                            : VariantBindingState.ALIGNED)
                    .setConfidenceScore(review.getScore())
                    .setConfidenceLevel(review.getConfidenceLevel())
                    .setManualLocked(false)
                    .setActive(true)
                    .setCreatedByType("USER");
            v1BindingRepository.upsertActive(binding);

            review.setReviewState(VariantReviewState.RESOLVED)
                    .setRequiresUserAction(false)
                    .setReasonText("用户已确认系统建议");
            reviewRepository.upsert(review);
        });

        skuAutoAlignService.confirmLegacySuggestion(
                shopName,
                review.getThirdPlatformItemId(),
                review.getSuggestedOfferId(),
                review.getThirdPlatformSkuId(),
                review.getSuggestedOfferSkuId(),
                review.getScore(),
                review.getSuggestedMatchSource());
        return true;
    }

    private List<VariantAlignmentReview> resolveConfirmTargets(SkuAlignConfirmSuggestionsDTO dto) {
        String scope = dto.getTargetScope().trim().toUpperCase();
        List<VariantAlignmentReview> out = new ArrayList<>();
        switch (scope) {
            case "PAGE" -> {
                Map<String, Map<String, VariantAlignmentReview>> byShop =
                        reviewRepository.mapByShop(dto.getShopName());
                for (Map<String, VariantAlignmentReview> byVariant : byShop.values()) {
                    for (VariantAlignmentReview review : byVariant.values()) {
                        if (review.getReviewState() == VariantReviewState.SUGGESTED) {
                            out.add(review);
                        }
                    }
                }
            }
            case "PRODUCT" -> {
                if (dto.getProductIds() == null || dto.getProductIds().isEmpty()) {
                    throw new CustomException("PRODUCT scope requires productIds");
                }
                for (String productId : dto.getProductIds()) {
                    if (StringUtils.isBlank(productId)) {
                        continue;
                    }
                    Map<String, VariantAlignmentReview> reviews =
                            reviewRepository.mapByProduct(dto.getShopName(), productId.trim());
                    for (VariantAlignmentReview review : reviews.values()) {
                        if (review.getReviewState() == VariantReviewState.SUGGESTED) {
                            out.add(review);
                        }
                    }
                }
            }
            case "VARIANTS" -> {
                if (dto.getVariantIds() == null || dto.getVariantIds().isEmpty()) {
                    throw new CustomException("VARIANTS scope requires variantIds");
                }
                Set<String> seen = new LinkedHashSet<>();
                for (String variantId : dto.getVariantIds()) {
                    if (StringUtils.isBlank(variantId) || !seen.add(variantId.trim())) {
                        continue;
                    }
                    reviewRepository.findByVariant(dto.getShopName(), variantId.trim())
                            .filter(r -> r.getReviewState() == VariantReviewState.SUGGESTED)
                            .ifPresent(out::add);
                }
            }
            default -> throw new CustomException("unknown targetScope: " + dto.getTargetScope());
        }
        return out;
    }

    private void maybeRecordManualAlias(String shopName, String variantId, String targetSpec) {
        if (StringUtils.isBlank(targetSpec)) {
            return;
        }
        String productId = thirdPlatformSkuRepository.findItemIdBySkuId(shopName, variantId).orElse(null);
        if (productId == null) {
            return;
        }
        ThirdPlatformSku variant = thirdPlatformSkuRepository.listByItem(shopName, productId).stream()
                .filter(v -> variantId.equals(v.getThirdPlatformSkuId()))
                .findFirst()
                .orElse(null);
        if (variant == null) {
            return;
        }
        String sourceText = firstNonBlank(variant.getOption1(), variant.getOption2(), variant.getOption3());
        if (StringUtils.isBlank(sourceText) || sourceText.equalsIgnoreCase(targetSpec.trim())) {
            return;
        }
        aliasKnowledgeRepository.upsertAlias(new SkuAlignAliasKnowledgeDTO()
                .setShopName(shopName)
                .setSourceText(sourceText.trim())
                .setTargetText(targetSpec.trim())
                .setDerivedFrom("MANUAL_CORRECTION"));
    }

    private static MatchSource parseMatchSource(String raw) {
        if (StringUtils.isBlank(raw)) {
            return MatchSource.RULE;
        }
        try {
            return MatchSource.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MatchSource.RULE;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (StringUtils.isNotBlank(v)) {
                return v.trim();
            }
        }
        return null;
    }
}
