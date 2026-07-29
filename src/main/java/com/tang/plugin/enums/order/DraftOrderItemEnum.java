package com.tang.plugin.enums.order;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DraftOrderItemEnum {
    AWAITING(1, "待处理"),
    AWAITING_PAYMENT(2, "待支付"),
    PROCESSING(3, "备货中"),
    AWAITING_SHIPMENT(4, "待发货"),
    AWAITING_FULFILLMENT(5, "待送达"),
    FULFILLED(6, "已完结"),
    CANCELED(9, "已取消"),
    REFUNDED(10, "已退款"),
    INVALID(11, "已失效");

    private final Integer code;
    private final String desc;

    public static DraftOrderItemEnum ofCode(Integer code) {
        if (code == null) return null;
        for (DraftOrderItemEnum e : values()) {
            if (e.code.equals(code)) return e;
        }
        return null;
    }
}
