package com.tang.plugin.enums.procurement;

/**
 * Consumer-side receipt status, orthogonal to delivery/task status.
 * RECEIVED = consumer acknowledged receipt; ACCEPTED = consumer took ownership (drives outbox ack).
 * One-way RECEIVED -> ACCEPTED in P1; no revert / requeue.
 */
public enum ProcurementConsumptionStatus {
    RECEIVED,
    ACCEPTED
}
