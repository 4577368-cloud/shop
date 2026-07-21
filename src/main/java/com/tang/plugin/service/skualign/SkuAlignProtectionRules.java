package com.tang.plugin.service.skualign;

import com.tang.plugin.domain.entity.skualign.VariantSkuBinding;
import com.tang.plugin.enums.skualign.VariantBindingState;
import com.tang.plugin.enums.skualign.VariantReviewState;
import com.tang.plugin.enums.match.MatchSource;

/**
 * V1 cover protection rules — enforced before any auto-align / rerun / confirm batch write.
 */
public final class SkuAlignProtectionRules {

    private SkuAlignProtectionRules() {
    }

    /** Auto tasks must skip manual-locked and blocked variants. */
    public static boolean isProtectedFromAutoOverwrite(VariantSkuBinding binding) {
        if (binding == null || !binding.isActive()) {
            return false;
        }
        if (binding.isManualLocked()) {
            return true;
        }
        return binding.getBindingState() == VariantBindingState.BLOCKED;
    }

    /** confirm-suggestions may only promote SUGGESTED rows that are not protected. */
    public static boolean canConfirmSuggestion(VariantSkuBinding existingBinding,
                                               VariantReviewState reviewState) {
        if (reviewState != VariantReviewState.SUGGESTED) {
            return false;
        }
        return !isProtectedFromAutoOverwrite(existingBinding);
    }

    /** Manual bind always wins; sets lock. */
    public static VariantSkuBinding manualBindTemplate(VariantSkuBinding row) {
        row.setMatchSource(MatchSource.MANUAL);
        row.setManualLocked(true);
        row.setCreatedByType("USER");
        return row;
    }

    /** Blocked variant: no offer ids required. */
    public static boolean isBlockedBinding(VariantSkuBinding binding) {
        return binding != null
                && binding.isActive()
                && binding.getBindingState() == VariantBindingState.BLOCKED;
    }

    /**
     * HIGH confidence (incl. single-SKU offers) auto-activates for all shop origins.
     * MEDIUM and below stay in review — {@code internalOrigin} kept for call-site compat only.
     */
    public static boolean mayAutoActivateBinding(boolean internalOrigin, ConfidenceTier tier) {
        return tier == ConfidenceTier.HIGH;
    }

    public enum ConfidenceTier {
        HIGH, MEDIUM, LOW;

        public static ConfidenceTier fromScore(double score, boolean singleSkuOffer) {
            if (singleSkuOffer || score >= 0.80d) {
                return HIGH;
            }
            if (score >= 0.50d) {
                return MEDIUM;
            }
            return LOW;
        }
    }
}
