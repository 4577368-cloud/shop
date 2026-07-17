package com.tang.plugin.utils;

/**
 * Order lock key helpers — align with docs OrderBizUtils pattern.
 */
public final class OrderBizUtils {

    public static final String OPERATION_ORDER_LOCK_KEY = "order:lock:%s";

    private OrderBizUtils() {
    }

    public static String operationLockKey(String outerOrderId) {
        return String.format(OPERATION_ORDER_LOCK_KEY, outerOrderId);
    }
}
