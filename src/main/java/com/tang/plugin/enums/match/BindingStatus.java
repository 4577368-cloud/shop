package com.tang.plugin.enums.match;

/**
 * Lifecycle of a platform-SKU ↔ Tangbuy-SKU binding.
 *
 * <p>{@code PENDING} is an AI-suggested binding that has not been confirmed by a human yet: it is
 * surfaced for review but is NOT treated as a formal binding by order/publish routing (which only
 * uses {@code ACTIVE}). Confirming ("确认无误") promotes PENDING → ACTIVE; unbinding soft-deletes to
 * {@code INACTIVE}.
 */
public enum BindingStatus {
    /** AI-suggested, awaiting human confirmation. Not used for order/publish routing. */
    PENDING,
    ACTIVE,
    INACTIVE
}
