package com.tang.plugin.constant;

/**
 * RocketMQ topic names (aligned with tang-plugin PluginMqConstant + dropship extras).
 */
public final class PluginMqConstant {
    private PluginMqConstant() {}

    public static final class TOPIC {
        public static final String PLUGIN_DRAFT_SINGLE_CHANGE_TOPIC = "plugin_draft_single_change_topic";
        public static final String PLUGIN_ORDER_PRODUCT_TOPIC = "plugin_order_product_topic";
        public static final String PLUGIN_ORDER_RELATION_PRODUCT_TOPIC = "plugin_order_relation_product_topic";
        public static final String PLUGIN_ORDER_STATE_CHANGE_TOPIC = "plugin_order_state_change_topic";
        public static final String PLUGIN_ORDER_PACKAGE_TOPIC = "plugin_order_package_topic";
        public static final String PLUGIN_ORDER_REFUND_TOPIC = "plugin_order_refund_topic";
        public static final String PLUGIN_ORDER_EXPRESS_UPDATE_TOPIC = "plugin_order_express_update_topic";
        public static final String PLUGIN_ORDER_REPAIR_FEE_TOPIC = "plugin_order_repair_fee_topic";
        public static final String PLUGIN_WEBHOOK_TOPIC = "PLUGIN_WEBHOOK_TOPIC";
    }
}
