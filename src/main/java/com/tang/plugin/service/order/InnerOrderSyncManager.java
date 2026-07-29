package com.tang.plugin.service.order;

import com.tang.plugin.domain.dto.order.UniOrderCreateResDTO;
import com.tang.plugin.domain.entity.order.TDraftOrderDO;
import com.tang.plugin.domain.entity.order.TDraftOrderLineDO;
import com.tang.plugin.domain.query.order.DraftOrderPackageCreateReq;
import com.tang.plugin.domain.query.order.DraftOrderPurchaseReq;

import java.util.List;

public interface InnerOrderSyncManager {
    UniOrderCreateResDTO uniOrderByLines(DraftOrderPurchaseReq req, Long userId,
                                         List<TDraftOrderLineDO> lines,
                                         DraftOrderPackageCreateReq packageCreateInfo);

    void syncOrderStatus(Long orderId, Integer status);
}
