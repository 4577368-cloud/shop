package com.tang.plugin.service.skualign;

import com.tang.plugin.domain.entity.skualign.VariantSkuBinding;
import com.tang.plugin.enums.match.MatchSource;
import com.tang.plugin.enums.skualign.VariantBindingState;
import com.tang.plugin.enums.skualign.VariantReviewState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkuAlignProtectionRulesTest {

    @Test
    void manualLockedBindingIsProtectedFromAutoOverwrite() {
        VariantSkuBinding locked = new VariantSkuBinding()
                .setActive(true)
                .setManualLocked(true)
                .setBindingState(VariantBindingState.ALIGNED);
        assertTrue(SkuAlignProtectionRules.isProtectedFromAutoOverwrite(locked));
    }

    @Test
    void blockedBindingIsProtectedFromAutoOverwrite() {
        VariantSkuBinding blocked = new VariantSkuBinding()
                .setActive(true)
                .setManualLocked(true)
                .setBindingState(VariantBindingState.BLOCKED);
        assertTrue(SkuAlignProtectionRules.isProtectedFromAutoOverwrite(blocked));
    }

    @Test
    void canConfirmOnlySuggestedWithoutLock() {
        VariantSkuBinding unlocked = new VariantSkuBinding()
                .setActive(true)
                .setManualLocked(false)
                .setBindingState(VariantBindingState.ALIGNED);
        assertTrue(SkuAlignProtectionRules.canConfirmSuggestion(unlocked, VariantReviewState.SUGGESTED));
        assertFalse(SkuAlignProtectionRules.canConfirmSuggestion(unlocked, VariantReviewState.UNMAPPED));
    }

    @Test
    void cannotConfirmWhenManualLocked() {
        VariantSkuBinding locked = new VariantSkuBinding()
                .setActive(true)
                .setManualLocked(true)
                .setMatchSource(MatchSource.MANUAL)
                .setBindingState(VariantBindingState.ALIGNED);
        assertFalse(SkuAlignProtectionRules.canConfirmSuggestion(locked, VariantReviewState.SUGGESTED));
    }

    @Test
    void highConfidenceAutoActivatesRegardlessOfOrigin() {
        assertTrue(SkuAlignProtectionRules.mayAutoActivateBinding(
                false, SkuAlignProtectionRules.ConfidenceTier.HIGH));
        assertTrue(SkuAlignProtectionRules.mayAutoActivateBinding(
                true, SkuAlignProtectionRules.ConfidenceTier.HIGH));
    }

    @Test
    void mediumConfidenceNeverAutoActivates() {
        assertFalse(SkuAlignProtectionRules.mayAutoActivateBinding(
                true, SkuAlignProtectionRules.ConfidenceTier.MEDIUM));
        assertFalse(SkuAlignProtectionRules.mayAutoActivateBinding(
                false, SkuAlignProtectionRules.ConfidenceTier.MEDIUM));
    }
}
