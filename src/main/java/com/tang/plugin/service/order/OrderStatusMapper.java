package com.tang.plugin.service.order;

import com.tang.plugin.enums.order.DraftOrderItemEnum;
import com.tang.plugin.enums.order.OrderStatusTab;

/**
 * Maps draft / warehouse codes onto FE order-center tabs.
 */
public final class OrderStatusMapper {
    private OrderStatusMapper() {}

    public static OrderStatusTab fromDraftStatus(Integer draftStatus) {
        DraftOrderItemEnum e = DraftOrderItemEnum.ofCode(draftStatus);
        if (e == null) return null;
        return switch (e) {
            case AWAITING -> OrderStatusTab.PENDING_ORDER;
            case AWAITING_PAYMENT -> OrderStatusTab.PENDING_PAYMENT;
            case PROCESSING -> OrderStatusTab.PREPARING;
            case AWAITING_SHIPMENT -> OrderStatusTab.PENDING_SHIPMENT;
            case AWAITING_FULFILLMENT -> OrderStatusTab.IN_TRANSIT;
            case FULFILLED -> OrderStatusTab.DELIVERED;
            case CANCELED, REFUNDED, INVALID -> OrderStatusTab.CANCELED;
        };
    }

    /**
     * Fine-grain override from Admin goodsStatus (subset).
     * null = keep draft mapping.
     */
    public static OrderStatusTab fromGoodsStatus(Integer goodsStatus) {
        if (goodsStatus == null) return null;
        return switch (goodsStatus) {
            case 2, 36, 47, 55 -> OrderStatusTab.PENDING_SUPPLEMENT;
            case 5, 6 -> OrderStatusTab.PENDING_SHIPMENT;
            case 30 -> OrderStatusTab.IN_TRANSIT;
            case 8, 9, 31, 45, 48, 50 -> OrderStatusTab.DELIVERED;
            case 11, 13, 24, 32, 34, 49, 53 -> OrderStatusTab.CANCELED;
            default -> null;
        };
    }

    /** Exception badge codes aligned with FE {@code order.procurement.exception.*}. */
    public static String exceptionTagFromGoodsStatus(Integer goodsStatus) {
        if (goodsStatus == null) return null;
        return switch (goodsStatus) {
            case 11, 13, 24, 32, 34, 49, 53 -> "canceled";
            case 40, 41 -> "return_in_progress";
            case 42, 43 -> "exchange_in_progress";
            case 44 -> "refused_sign";
            case 51, 52 -> "frozen";
            case 54, 56, 57 -> "exception_handling";
            default -> null;
        };
    }

    public static OrderStatusTab resolve(Integer draftStatus, Integer goodsStatus) {
        OrderStatusTab fromGoods = fromGoodsStatus(goodsStatus);
        if (fromGoods != null) return fromGoods;
        return fromDraftStatus(draftStatus);
    }
}
