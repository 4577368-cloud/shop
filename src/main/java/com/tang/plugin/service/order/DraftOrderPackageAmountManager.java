package com.tang.plugin.service.order;

import com.tang.plugin.domain.entity.order.TDraftOrderDO;
import com.tang.plugin.domain.query.order.DraftOrderPackageCreateReq;
import com.tang.plugin.domain.vo.order.DraftOrderPurchaseAmountVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Lite package fee calculator — no coupons / tier discounts.
 * Uses purchase_amount + optional flat package estimate from line selection.
 */
@Service
public class DraftOrderPackageAmountManager {

    public DraftOrderPurchaseAmountVO cal(TDraftOrderDO order, DraftOrderPackageCreateReq packageCreateInfo) {
        BigDecimal goods = order.getPurchaseAmount() == null ? BigDecimal.ZERO : order.getPurchaseAmount();
        // Lite: package fee placeholder 0 until logistics quote is wired into purchase preview.
        BigDecimal pkg = BigDecimal.ZERO;
        return new DraftOrderPurchaseAmountVO()
                .setGoodsAmountCny(goods)
                .setPackageAmountCny(pkg)
                .setTotalCny(goods.add(pkg));
    }
}
