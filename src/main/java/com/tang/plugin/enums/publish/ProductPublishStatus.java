package com.tang.plugin.enums.publish;

/**
 * Publish lifecycle for a Tangbuy catalog candidate → Shopify product.
 * <ul>
 *   <li>{@code PENDING}    — ledger row created, no publish attempted yet.</li>
 *   <li>{@code PUBLISHING} — a real publish attempt is in flight (set right before the Shopify write;
 *       attempts is incremented on entry). Guards against concurrent duplicate creation.</li>
 *   <li>{@code PUBLISHED}  — Shopify product created, GIDs filled; terminal, write-once published_at.</li>
 *   <li>{@code FAILED}     — last attempt failed; retryable (can re-enter PUBLISHING).</li>
 * </ul>
 * Orthogonal to any procurement/order status. No real Shopify write exists yet (M1-3 base).
 */
public enum ProductPublishStatus {
    PENDING,
    PUBLISHING,
    PUBLISHED,
    FAILED
}
