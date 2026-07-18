package com.tang.plugin.enums.procurement;

/**
 * Minimal procurement-task lifecycle in the integration layer (outbox record only).
 * PENDING on creation; CANCELLED for soft cancel. No execution states in P1.
 */
public enum ProcurementTaskStatus {
    PENDING,
    CANCELLED
}
