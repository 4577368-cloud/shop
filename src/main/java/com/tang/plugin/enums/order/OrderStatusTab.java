package com.tang.plugin.enums.order;

/**
 * FE order-center tab keys (must match src/lib/order/types.ts OrderStatus).
 */
public enum OrderStatusTab {
    PENDING_ORDER("pendingOrder"),
    PENDING_SUPPLEMENT("pendingSupplement"),
    PENDING_PAYMENT("pendingPayment"),
    PREPARING("preparing"),
    PENDING_SHIPMENT("pendingShipment"),
    IN_TRANSIT("inTransit"),
    DELIVERED("delivered"),
    CANCELED("canceled");

    private final String code;

    OrderStatusTab(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
