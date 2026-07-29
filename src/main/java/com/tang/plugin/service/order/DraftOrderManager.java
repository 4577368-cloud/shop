package com.tang.plugin.service.order;

import com.tang.plugin.domain.query.order.DraftOrderFillReq;
import com.tang.plugin.domain.query.order.DraftOrderPurchaseReq;
import com.tang.plugin.domain.query.order.DraftOrderRefundReq;
import com.tang.plugin.domain.vo.order.CreateDraftOrderPurchaseVO;
import com.tang.plugin.domain.vo.order.DraftOrderFillVO;
import com.tang.plugin.domain.vo.order.DraftOrderPackageDetailVO;
import com.tang.plugin.domain.vo.order.DraftOrderPurchaseAmountVO;

public interface DraftOrderManager {
    CreateDraftOrderPurchaseVO purchaseOrder(Long userId, DraftOrderPurchaseReq req);
    DraftOrderPurchaseAmountVO calDraftPurchasedOrderAmount(Long userId, DraftOrderPurchaseReq req);
    DraftOrderFillVO fillPackageAmount(DraftOrderFillReq req);
    DraftOrderPackageDetailVO packageInfo(Long orderId);
    void refundOrderLine(Long userId, DraftOrderRefundReq req);
}
