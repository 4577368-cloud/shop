package com.tang.plugin.utils.constant;

public final class RedisPrefixConstants {

    private RedisPrefixConstants() {
    }

    public static final String PUBLISH_PRODUCT_SEARCH_PREFIX = "publish:product:";
    public static final String ORDER_CREATE_LOCK_PREFIX = "order:create:";
    public static final String ORDER_OPERATION_LOCK_PREFIX = "order:op:";
}
