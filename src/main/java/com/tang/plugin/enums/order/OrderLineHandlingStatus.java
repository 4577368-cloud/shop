package com.tang.plugin.enums.order;

/**
 * Handling status for UNBOUND order lines. Null in storage is interpreted as PENDING.
 * Only meaningful for lines with binding_status = UNBOUND and del_flag = 0.
 */
public enum OrderLineHandlingStatus {
    PENDING,
    IGNORED,
    RESOLVED
}
