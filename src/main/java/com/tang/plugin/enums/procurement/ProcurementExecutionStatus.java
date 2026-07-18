package com.tang.plugin.enums.procurement;

/**
 * Execution placeholder status (a new downstream dimension), orthogonal to task/delivery/consumption.
 * P1 is a stub only: PENDING_EXECUTION on create; COMPLETED_STUB is a manual placeholder marker and
 * does NOT represent any real supplier ordering or procurement execution.
 */
public enum ProcurementExecutionStatus {
    PENDING_EXECUTION,
    COMPLETED_STUB
}
