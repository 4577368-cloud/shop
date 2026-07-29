package com.tang.plugin.domain.query.order;

import lombok.Data;

import java.util.List;

/**
 * Dropship purchase request (orderType=1 only). No orderLineStock / inquiry / materials.
 */
@Data
public class DraftOrderPurchaseReq {
    /** Internal draft order id (preferred). */
    private Long orderId;
    /** Shopify outer order id — resolved with shopName when orderId absent. */
    private String outerOrderId;
    private String shopName;
    /** Always 1 (EXTERNAL_PULL) for lite dropship. */
    private Integer orderType = 1;
    private DraftOrderPackageCreateReq packageCreateInfo;
    private Long addressId;
}
