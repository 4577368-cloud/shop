package com.tang.plugin.enums.order;

/**
 * Binding resolution status for an order line. P1 only distinguishes bound vs unbound;
 * query errors are degraded to UNBOUND and never block order ingestion.
 */
public enum OrderLineBindingStatus {
    BOUND,
    UNBOUND
}
