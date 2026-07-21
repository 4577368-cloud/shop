package com.tang.plugin.service.skualign;

import com.tang.plugin.domain.entity.skualign.VariantSkuBinding;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import com.tang.plugin.enums.match.MatchSource;
import com.tang.plugin.enums.skualign.ConfidenceLevel;
import com.tang.plugin.enums.skualign.ReviewReasonCode;
import com.tang.plugin.enums.skualign.SourceRole;
import com.tang.plugin.enums.skualign.VariantBindingState;
import com.tang.plugin.enums.skualign.VariantReviewState;
import com.tang.plugin.service.match.sku.SkuMatcher;
import com.tang.plugin.service.match.sku.SkuMatcher.VariantAlignment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Maps rule-engine alignment output to V1 review states (SUGGESTED / UNMAPPED / NO_SOURCE / RESOLVED).
 */
public final class SkuReviewClassifier {

    private static final int SCORE_SCALE = 4;

    private SkuReviewClassifier() {
    }

    public record Classification(
            VariantReviewState reviewState,
            ReviewReasonCode reasonCode,
            String reasonText,
            boolean requiresUserAction,
            ConfidenceLevel confidenceLevel,
            BigDecimal score,
            String suggestedOfferId,
            String suggestedOfferSkuId,
            String suggestedMatchSource,
            boolean writeLegacyPending,
            boolean writeV1ActiveBinding,
            VariantBindingState bindingState,
            SourceRole sourceRole
    ) {
        public VariantBindingState effectiveBindingState() {
            return bindingState != null ? bindingState : VariantBindingState.ALIGNED;
        }

        public SourceRole effectiveSourceRole() {
            return sourceRole != null ? sourceRole : SourceRole.PRIMARY;
        }
    }

    public static Classification classify(
            ThirdPlatformSku variant,
            List<com.tang.plugin.domain.dto.match.sku.OfferSkuVO> offerSkus,
            VariantAlignment alignment,
            String offerId,
            boolean internalOrigin,
            VariantSkuBinding existingV1Binding) {

        if (SkuAlignProtectionRules.isProtectedFromAutoOverwrite(existingV1Binding)) {
            return new Classification(
                    VariantReviewState.RESOLVED,
                    ReviewReasonCode.MANUAL_OVERRIDE,
                    "手动或阻断绑定已锁定，自动对齐跳过",
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    null,
                    null);
        }

        if (existingV1Binding != null
                && existingV1Binding.isActive()
                && existingV1Binding.getBindingState() != null
                && existingV1Binding.getOfferSkuId() != null) {
            return resolvedFromExisting(existingV1Binding);
        }

        boolean singleSku = offerSkus != null && offerSkus.size() == 1;

        if (SkuMatcher.hasOptionAbsentFromMatrix(variant, offerSkus)) {
            return new Classification(
                    VariantReviewState.NO_SOURCE,
                    ReviewReasonCode.NO_SKU_IN_MATRIX,
                    "货源规格表缺少该变体选项，无法映射",
                    true,
                    ConfidenceLevel.LOW,
                    toScore(alignment.score()),
                    null,
                    null,
                    null,
                    false,
                    false,
                    null,
                    null);
        }

        if (alignment.matched()) {
            SkuAlignProtectionRules.ConfidenceTier tier =
                    SkuAlignProtectionRules.ConfidenceTier.fromScore(alignment.score(), singleSku);
            ConfidenceLevel level = toConfidenceLevel(tier);
            BigDecimal score = toScore(alignment.score());
            ReviewReasonCode reason = singleSku ? ReviewReasonCode.SINGLE_SKU_OFFER : ReviewReasonCode.TOKEN_MATCH;
            String reasonText = singleSku
                    ? "单 SKU 货源，系统建议绑定"
                    : "规格 token 重叠 " + Math.round(alignment.score() * 100) + "%";

            if (SkuAlignProtectionRules.mayAutoActivateBinding(internalOrigin, tier)) {
                return new Classification(
                        VariantReviewState.RESOLVED,
                        reason,
                        reasonText,
                        false,
                        level,
                        score,
                        offerId,
                        alignment.skuId(),
                        MatchSource.RULE.name(),
                        false,
                        true,
                        null,
                        null);
            }

            return new Classification(
                    VariantReviewState.SUGGESTED,
                    reason,
                    reasonText,
                    true,
                    level,
                    score,
                    offerId,
                    alignment.skuId(),
                    MatchSource.RULE.name(),
                    true,
                    false,
                    null,
                    null);
        }

        return new Classification(
                VariantReviewState.UNMAPPED,
                ReviewReasonCode.TOKEN_MATCH,
                "规格相似度不足，需人工选择 SKU",
                true,
                ConfidenceLevel.LOW,
                toScore(alignment.score()),
                null,
                null,
                null,
                false,
                false,
                null,
                null);
    }

    /**
     * Supplement-offer fallback after primary matrix misses a variant (e.g. XXL on primary, present on supplement).
     */
    public static Classification classifySupplementOffer(
            ThirdPlatformSku variant,
            List<com.tang.plugin.domain.dto.match.sku.OfferSkuVO> supplementSkus,
            VariantAlignment alignment,
            String supplementOfferId,
            boolean internalOrigin,
            VariantSkuBinding existingV1Binding) {

        if (SkuAlignProtectionRules.isProtectedFromAutoOverwrite(existingV1Binding)) {
            return null;
        }
        if (alignment == null || !alignment.matched()) {
            return null;
        }
        if (SkuMatcher.hasOptionAbsentFromMatrix(variant, supplementSkus)) {
            return null;
        }

        boolean singleSku = supplementSkus != null && supplementSkus.size() == 1;
        SkuAlignProtectionRules.ConfidenceTier tier =
                SkuAlignProtectionRules.ConfidenceTier.fromScore(alignment.score(), singleSku);
        ConfidenceLevel level = toConfidenceLevel(tier);
        BigDecimal score = toScore(alignment.score());
        ReviewReasonCode reason = ReviewReasonCode.MULTI_SOURCE_REQUIRED;
        String reasonText = "补充货源匹配 " + Math.round(alignment.score() * 100) + "%";

        if (SkuAlignProtectionRules.mayAutoActivateBinding(internalOrigin, tier)) {
            return new Classification(
                    VariantReviewState.RESOLVED,
                    reason,
                    reasonText,
                    false,
                    level,
                    score,
                    supplementOfferId,
                    alignment.skuId(),
                    MatchSource.RULE.name(),
                    false,
                    true,
                    VariantBindingState.MULTI_SOURCE,
                    SourceRole.SUPPLEMENT);
        }

        return new Classification(
                VariantReviewState.SUGGESTED,
                reason,
                reasonText,
                true,
                level,
                score,
                supplementOfferId,
                alignment.skuId(),
                MatchSource.RULE.name(),
                true,
                false,
                VariantBindingState.MULTI_SOURCE,
                SourceRole.SUPPLEMENT);
    }

    private static Classification resolvedFromExisting(VariantSkuBinding binding) {
        return new Classification(
                VariantReviewState.RESOLVED,
                ReviewReasonCode.MANUAL_OVERRIDE,
                "变体已有生效绑定",
                false,
                binding.getConfidenceLevel(),
                binding.getConfidenceScore(),
                binding.getOfferId(),
                binding.getOfferSkuId(),
                binding.getMatchSource() != null ? binding.getMatchSource().name() : MatchSource.RULE.name(),
                false,
                false,
                binding.getBindingState(),
                binding.getSourceRole());
    }

    private static ConfidenceLevel toConfidenceLevel(SkuAlignProtectionRules.ConfidenceTier tier) {
        return switch (tier) {
            case HIGH -> ConfidenceLevel.HIGH;
            case MEDIUM -> ConfidenceLevel.MEDIUM;
            case LOW -> ConfidenceLevel.LOW;
        };
    }

    private static BigDecimal toScore(double score) {
        double clamped = Math.max(0d, Math.min(1d, score));
        return BigDecimal.valueOf(clamped).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }
}
