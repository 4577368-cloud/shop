package com.tang.plugin.enums.procurement;

/**
 * Runtime-derived health/anomaly codes for the task -> outbox -> consumer-receipt chain.
 * Never persisted, never a task/consumption state — purely computed at query time for ops/audit.
 */
public enum ProcurementChainAnomaly {

    /** PENDING + PENDING_DELIVERY, never pulled (attempts=0), created before the stale threshold. */
    STALE_PENDING_UNPULLED(Severity.WARN, "Task pending and never pulled beyond stale threshold"),
    /** PENDING + PENDING_DELIVERY, pulled at least once but last pulled before the stale threshold. */
    PULLED_NOT_DELIVERED(Severity.WARN, "Task pulled but never acked/accepted beyond stale threshold"),
    /** Has a RECEIVED receipt, no ACCEPTED, still PENDING_DELIVERY. */
    RECEIVED_NOT_ACCEPTED(Severity.WARN, "Consumer received but has not accepted"),
    /** DELIVERED but no ACCEPTED receipt — delivered via direct outbox ack (legitimate). */
    DELIVERED_WITHOUT_ACCEPT(Severity.INFO, "Delivered via direct outbox ack, no consumer accept receipt"),
    /** ACCEPTED receipt exists but delivery_status is not DELIVERED — ledger/outbox drift. */
    ACCEPTED_NOT_DELIVERED(Severity.ERROR, "Accepted receipt exists but task is not DELIVERED"),
    /** Task CANCELLED but consumption receipts exist. */
    CANCELLED_WITH_RECEIPTS(Severity.INFO, "Task cancelled but consumer receipts exist"),
    /** More than one distinct consumer accepted the same task (no claim in P1). */
    MULTI_CONSUMER_ACCEPTED(Severity.INFO, "Multiple consumers accepted the same task"),
    /**
     * Execution stub is COMPLETED_STUB while task_status is CANCELLED. An explainable, legal
     * combination (execution_status is orthogonal to task_status); surfaced here for reconciliation.
     */
    EXECUTION_COMPLETED_ON_CANCELLED(Severity.INFO,
            "Execution completed while task is cancelled (orthogonal, explainable)"),
    /** Execution stub still PENDING_EXECUTION, created before the stale threshold (never completed). */
    EXECUTION_PENDING_STALE(Severity.WARN, "Execution stub pending beyond stale threshold");

    public enum Severity {
        INFO,
        WARN,
        ERROR
    }

    private final Severity severity;
    private final String description;

    ProcurementChainAnomaly(Severity severity, String description) {
        this.severity = severity;
        this.description = description;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getDescription() {
        return description;
    }
}
