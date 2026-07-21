package com.tang.plugin.service.match.sku;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.TxManger;
import com.tang.plugin.domain.dto.match.SkuAutoAlignItemVO;
import com.tang.plugin.domain.dto.match.SkuAutoAlignResultVO;
import com.tang.plugin.domain.dto.match.sku.OfferDetailVO;
import com.tang.plugin.domain.entity.match.ShopProductBinding;
import com.tang.plugin.domain.entity.match.ShopProductMatchCandidate;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import com.tang.plugin.enums.PluginType;
import com.tang.plugin.enums.match.BindingStatus;
import com.tang.plugin.enums.match.MatchSource;
import com.tang.plugin.enums.match.MatchStatus;
import com.tang.plugin.repository.ShopProductBindingRepository;
import com.tang.plugin.repository.ShopProductMatchCandidateRepository;
import com.tang.plugin.repository.ThirdPlatformSkuRepository;
import com.tang.plugin.repository.skualign.VariantAlignmentReviewRepository;
import com.tang.plugin.repository.skualign.VariantSkuBindingRepository;
import com.tang.plugin.service.skualign.ProductPrimaryOfferResolver;
import com.tang.plugin.service.skualign.SkuAlignProtectionRules;
import com.tang.plugin.enums.skualign.VariantReviewState;
import com.tang.plugin.service.match.sku.SkuMatcher.VariantAlignment;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * S1-b1: auto-align a bound product's Shopify variants to the 1688 offer's SKU matrix, replacing the
 * A3-2b default-variant single binding with per-variant {@link MatchSource#RULE} bindings.
 *
 * <p>Reads the product-level offer from the existing ACTIVE binding (confirmed on the selection page),
 * fetches the offer's SKU matrix via {@link Crossborder1688ProductClient#queryProductDetail}, and matches
 * each variant with {@link SkuMatcher}. Confident matches are persisted in one transaction as a CONFIRMED
 * RULE candidate (carrying the overlap score + structured {@link SkuMatchReason}) plus an ACTIVE binding
 * sourced {@code AUTO_ALIGN}; unmatched variants are left untouched (no wrong auto-bind). Idempotent —
 * re-running rewrites the same anchored rows.
 */
@Slf4j
@Service
public class SkuAutoAlignService {

    public static final String ERR_NOT_BOUND = "NOT_BOUND";
    public static final String ERR_NO_VARIANT = "NO_VARIANT";
    public static final String ERR_NO_OFFER_SKU = "NO_OFFER_SKU";

    private static final String BIND_SOURCE_AUTO = "AUTO_ALIGN";
    private static final String BIND_SOURCE_MANUAL = "MANUAL";
    private static final String ALGO_RULE = "RULE";
    private static final int SCORE_SCALE = 4;

    @Resource
    private ShopProductBindingRepository shopProductBindingRepository;
    @Resource
    private ShopProductMatchCandidateRepository shopProductMatchCandidateRepository;
    @Resource
    private ThirdPlatformSkuRepository thirdPlatformSkuRepository;
    @Resource
    private Crossborder1688ProductClient crossborder1688ProductClient;
    @Resource
    private TxManger txManger;
    @Resource
    private ProductPrimaryOfferResolver productPrimaryOfferResolver;
    @Resource
    private VariantSkuBindingRepository v1BindingRepository;
    @Resource
    private VariantAlignmentReviewRepository reviewRepository;

    /**
     * Auto-align the variants of {@code thirdPlatformItemId}. When {@code offerId} is blank it is resolved
     * from the product's ACTIVE binding.
     *
     * @throws CustomException prefixed with {@link #ERR_NOT_BOUND} / {@link #ERR_NO_VARIANT} / {@link #ERR_NO_OFFER_SKU}.
     */
    public SkuAutoAlignResultVO autoAlign(String shopName, String thirdPlatformItemId, String offerId) {
        if (StringUtils.isAnyBlank(shopName, thirdPlatformItemId)) {
            throw new CustomException("auto-align requires shopName and thirdPlatformItemId");
        }
        String resolvedOffer = StringUtils.isNotBlank(offerId)
                ? offerId.trim()
                : productPrimaryOfferResolver.resolvePrimaryOffer(shopName, thirdPlatformItemId);

        List<ThirdPlatformSku> variants = thirdPlatformSkuRepository.listByItem(shopName, thirdPlatformItemId);
        if (variants.isEmpty()) {
            throw new CustomException(ERR_NO_VARIANT + ": 该商品无可用变体(SKU)，请重新同步商品后再对齐");
        }

        OfferDetailVO detail = crossborder1688ProductClient.queryProductDetail(resolvedOffer, "en");
        if (detail.getSkus() == null || detail.getSkus().isEmpty()) {
            throw new CustomException(ERR_NO_OFFER_SKU + ": 1688 offer(" + resolvedOffer + ") 未返回可用 SKU");
        }

        List<VariantAlignment> alignments = SkuMatcher.align(variants, detail.getSkus());
        String detailUrl = "https://detail.1688.com/offer/" + resolvedOffer + ".html";
        boolean singleSkuOffer = detail.getSkus().size() == 1;

        List<SkuAutoAlignItemVO> items = new ArrayList<>();
        int[] matched = {0};
        txManger.run(() -> {
            for (VariantAlignment a : alignments) {
                SkuAutoAlignItemVO item = new SkuAutoAlignItemVO()
                        .setThirdPlatformSkuId(a.variantGid())
                        .setOptionLabel(a.optionLabel())
                        .setMatched(a.matched())
                        .setScore(toScore(a.score()));
                if (a.matched()) {
                    item.setTangbuySkuId(a.skuId()).setTangbuySkuSpec(a.specLabel());
                    persist(shopName, thirdPlatformItemId, resolvedOffer, a, detailUrl, singleSkuOffer);
                    matched[0]++;
                }
                items.add(item);
            }
            log.info("SkuAutoAlign shopName={} itemId={} offerId={} variants={} matched={}",
                    shopName, thirdPlatformItemId, resolvedOffer, alignments.size(), matched[0]);
        });

        return new SkuAutoAlignResultVO()
                .setThirdPlatformItemId(thirdPlatformItemId)
                .setOfferId(resolvedOffer)
                .setTotalVariants(variants.size())
                .setMatchedCount(matched[0])
                .setItems(items);
    }

    /**
     * Dual-write helper for V1 engine: legacy MEDIUM-confidence matches → PENDING (needs_review).
     */
    public void persistRuleMatches(String shopName,
                                   String thirdPlatformItemId,
                                   String offerId,
                                   List<VariantAlignment> alignments,
                                   String detailUrl) {
        persistRuleMatches(shopName, thirdPlatformItemId, offerId, alignments, detailUrl, BindingStatus.PENDING);
    }

    /** Dual-write ACTIVE legacy bindings (internal-origin auto-activate path). */
    public void persistActiveRuleMatches(String shopName,
                                         String thirdPlatformItemId,
                                         String offerId,
                                         List<VariantAlignment> alignments,
                                         String detailUrl) {
        persistRuleMatches(shopName, thirdPlatformItemId, offerId, alignments, detailUrl, BindingStatus.ACTIVE);
    }

    private void persistRuleMatches(String shopName,
                                    String thirdPlatformItemId,
                                    String offerId,
                                    List<VariantAlignment> alignments,
                                    String detailUrl,
                                    BindingStatus bindStatus) {
        txManger.run(() -> {
            for (VariantAlignment a : alignments) {
                if (a.matched()) {
                    persist(shopName, thirdPlatformItemId, offerId, a, detailUrl, bindStatus);
                }
            }
        });
    }

    /** Dual-write helper for V1 confirm-suggestions: promote PENDING or write ACTIVE legacy row. */
    public void confirmLegacySuggestion(String shopName,
                                        String thirdPlatformItemId,
                                        String offerId,
                                        String thirdPlatformSkuId,
                                        String tangbuySkuId,
                                        BigDecimal score,
                                        String matchSourceName) {
        if (StringUtils.isAnyBlank(shopName, thirdPlatformSkuId, offerId, tangbuySkuId)) {
            return;
        }
        Optional<ShopProductBinding> existing =
                shopProductBindingRepository.findBindableBySkuId(shopName, thirdPlatformSkuId);
        if (existing.isPresent() && BIND_SOURCE_MANUAL.equals(existing.get().getBindSource())) {
            log.info("SkuAutoAlign confirm skip MANUAL locked variant={}", thirdPlatformSkuId);
            return;
        }
        int activated = shopProductBindingRepository.activateBySkuId(shopName, thirdPlatformSkuId);
        if (activated > 0) {
            log.info("SkuAutoAlign confirm ACK shopName={} variant={} rows={}",
                    shopName, thirdPlatformSkuId, activated);
            return;
        }
        String spec = tangbuySkuId;
        String detailUrl = "https://detail.1688.com/offer/" + offerId + ".html";
        MatchSource source = parseMatchSource(matchSourceName);
        String reason = SkuMatchReason.encode(
                source == MatchSource.RULE ? ALGO_RULE : source.name(),
                score != null ? score.doubleValue() : 1.0d,
                tangbuySkuId,
                spec,
                detailUrl);
        ShopProductMatchCandidate candidate = new ShopProductMatchCandidate()
                .setShopName(shopName)
                .setShopType(PluginType.SHOPIFY.getCode())
                .setThirdPlatformItemId(thirdPlatformItemId)
                .setThirdPlatformSkuId(thirdPlatformSkuId)
                .setTangbuyProductId(offerId)
                .setTangbuySkuId(tangbuySkuId)
                .setMatchSource(source)
                .setMatchScore(score != null ? score : BigDecimal.ONE.setScale(SCORE_SCALE, RoundingMode.HALF_UP))
                .setMatchReason(reason)
                .setStatus(MatchStatus.CONFIRMED);
        txManger.run(() -> {
            Long candidateId = shopProductMatchCandidateRepository.upsert(candidate);
            ShopProductBinding binding = new ShopProductBinding()
                    .setShopName(shopName)
                    .setShopType(PluginType.SHOPIFY.getCode())
                    .setThirdPlatformItemId(thirdPlatformItemId)
                    .setThirdPlatformSkuId(thirdPlatformSkuId)
                    .setTangbuyProductId(offerId)
                    .setTangbuySkuId(tangbuySkuId)
                    .setBindSource(BIND_SOURCE_AUTO)
                    .setCandidateId(candidateId)
                    .setBindStatus(BindingStatus.ACTIVE);
            shopProductBindingRepository.upsertActive(binding);
        });
    }

    /** "确认无误": promote a single variant's PENDING binding to ACTIVE. Idempotent. */
    public void acknowledge(String shopName, String thirdPlatformSkuId) {
        if (StringUtils.isAnyBlank(shopName, thirdPlatformSkuId)) {
            throw new CustomException("acknowledge requires shopName and thirdPlatformSkuId");
        }
        int rows = shopProductBindingRepository.activateBySkuId(shopName, thirdPlatformSkuId);
        log.info("SkuBinding ACK shopName={} thirdPlatformSkuId={} rows={}", shopName, thirdPlatformSkuId, rows);
    }

    /** "取消关联": soft-unbind a single variant's binding (PENDING or ACTIVE). Idempotent. */
    public void unbind(String shopName, String thirdPlatformSkuId) {
        if (StringUtils.isAnyBlank(shopName, thirdPlatformSkuId)) {
            throw new CustomException("unbind requires shopName and thirdPlatformSkuId");
        }
        txManger.run(() -> {
            int rows = shopProductBindingRepository.deactivateBySkuId(shopName, thirdPlatformSkuId);
            v1BindingRepository.deactivateByVariant(shopName, thirdPlatformSkuId);
            reviewRepository.findByVariant(shopName, thirdPlatformSkuId).ifPresent(review -> {
                review.setReviewState(VariantReviewState.UNMAPPED)
                        .setSuggestedOfferId(null)
                        .setSuggestedOfferSkuId(null)
                        .setSuggestedSourceRole(null)
                        .setSuggestedMatchSource(null)
                        .setRequiresUserAction(true)
                        .setReasonText("用户已取消关联，需重新选择 SKU");
                reviewRepository.upsert(review);
            });
            log.info("SkuBinding UNBIND shopName={} thirdPlatformSkuId={} legacyRows={}",
                    shopName, thirdPlatformSkuId, rows);
        });
    }

    private void persist(String shopName,
                         String itemId,
                         String offerId,
                         VariantAlignment a,
                         String detailUrl,
                         boolean singleSkuOffer) {
        persist(shopName, itemId, offerId, a, detailUrl, resolveLegacyBindStatus(a, singleSkuOffer));
    }

    private void persist(String shopName,
                         String itemId,
                         String offerId,
                         VariantAlignment a,
                         String detailUrl,
                         BindingStatus bindStatus) {
        Optional<ShopProductBinding> existing =
                shopProductBindingRepository.findBindableBySkuId(shopName, a.variantGid());
        if (existing.isPresent() && BIND_SOURCE_MANUAL.equals(existing.get().getBindSource())) {
            log.debug("SkuAutoAlign skip MANUAL locked variant={}", a.variantGid());
            return;
        }
        String reason = SkuMatchReason.encode(ALGO_RULE, a.score(), a.skuId(), a.specLabel(), detailUrl);
        ShopProductMatchCandidate candidate = new ShopProductMatchCandidate()
                .setShopName(shopName)
                .setShopType(PluginType.SHOPIFY.getCode())
                .setThirdPlatformItemId(itemId)
                .setThirdPlatformSkuId(a.variantGid())
                .setTangbuyProductId(offerId)
                .setTangbuySkuId(a.skuId())
                .setMatchSource(MatchSource.RULE)
                .setMatchScore(toScore(a.score()))
                .setMatchReason(reason)
                .setStatus(MatchStatus.CONFIRMED);
        Long candidateId = shopProductMatchCandidateRepository.upsert(candidate);

        ShopProductBinding binding = new ShopProductBinding()
                .setShopName(shopName)
                .setShopType(PluginType.SHOPIFY.getCode())
                .setThirdPlatformItemId(itemId)
                .setThirdPlatformSkuId(a.variantGid())
                .setTangbuyProductId(offerId)
                .setTangbuySkuId(a.skuId())
                .setBindSource(BIND_SOURCE_AUTO)
                .setCandidateId(candidateId)
                .setBindStatus(bindStatus);
        shopProductBindingRepository.upsert(binding, bindStatus);
    }

    /** HIGH / single-SKU → ACTIVE; MEDIUM → PENDING (needs_review). */
    static BindingStatus resolveLegacyBindStatus(VariantAlignment alignment, boolean singleSkuOffer) {
        SkuAlignProtectionRules.ConfidenceTier tier =
                SkuAlignProtectionRules.ConfidenceTier.fromScore(alignment.score(), singleSkuOffer);
        return tier == SkuAlignProtectionRules.ConfidenceTier.HIGH
                ? BindingStatus.ACTIVE
                : BindingStatus.PENDING;
    }

    private static BigDecimal toScore(double score) {
        double clamped = Math.max(0d, Math.min(1d, score));
        return BigDecimal.valueOf(clamped).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
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
}
