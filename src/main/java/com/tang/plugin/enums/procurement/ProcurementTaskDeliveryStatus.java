package com.tang.plugin.enums.procurement;

/**
 * Outbox delivery status, orthogonal to {@link ProcurementTaskStatus}.
 * PENDING_DELIVERY on creation; DELIVERED only via ack (ack-based at-least-once). No claim in P1.
 */
public enum ProcurementTaskDeliveryStatus {
    PENDING_DELIVERY,
    DELIVERED
}
