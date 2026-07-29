package com.tang.plugin.controller.draft.order;

import com.alibaba.fastjson2.JSON;
import com.tang.plugin.domain.query.order.DraftOrderFillReq;
import com.tang.plugin.domain.query.order.DraftOrderPurchaseReq;
import com.tang.plugin.domain.query.order.DraftOrderRefundReq;
import com.tang.plugin.domain.vo.order.CreateDraftOrderPurchaseVO;
import com.tang.plugin.domain.vo.order.DraftOrderFillVO;
import com.tang.plugin.domain.vo.order.DraftOrderPackageDetailVO;
import com.tang.plugin.domain.vo.order.DraftOrderPurchaseAmountVO;
import com.tang.plugin.service.order.DraftOrderManager;
import com.tang.plugin.service.user.ShopAccessGuard;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lite dropship purchase APIs (orderType=1).
 * Returns bare VO (same style as other /api/plugin/** controllers).
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/draft/order")
public class DraftOrderController {

    @Resource private DraftOrderManager draftOrderManager;
    @Resource private ShopAccessGuard shopAccessGuard;

    @PostMapping("/purchaseOrder")
    public CreateDraftOrderPurchaseVO purchaseOrder(HttpServletRequest request,
                                                    @Valid @RequestBody DraftOrderPurchaseReq req) {
        Long userId = (Long) request.getAttribute("userId");
        if (StringUtils.isNotBlank(req.getShopName())) {
            shopAccessGuard.assertOwner(userId, req.getShopName());
        }
        log.info("purchaseOrder userId={} req={}", userId, JSON.toJSONString(req));
        return draftOrderManager.purchaseOrder(userId, req);
    }

    @PostMapping("/calDraftPurchasedAmount")
    public DraftOrderPurchaseAmountVO calDraftPurchasedAmount(HttpServletRequest request,
                                                              @Valid @RequestBody DraftOrderPurchaseReq req) {
        Long userId = (Long) request.getAttribute("userId");
        if (StringUtils.isNotBlank(req.getShopName())) {
            shopAccessGuard.assertOwner(userId, req.getShopName());
        }
        return draftOrderManager.calDraftPurchasedOrderAmount(userId, req);
    }

    @PostMapping("/fillAmount")
    public DraftOrderFillVO fillAmount(HttpServletRequest request,
                                       @Valid @RequestBody DraftOrderFillReq req) {
        Long userId = (Long) request.getAttribute("userId");
        req.setUserId(userId);
        return draftOrderManager.fillPackageAmount(req);
    }

    @PostMapping("/refundOrder")
    public void refundOrder(HttpServletRequest request,
                            @Valid @RequestBody DraftOrderRefundReq req) {
        Long userId = (Long) request.getAttribute("userId");
        draftOrderManager.refundOrderLine(userId, req);
    }

    @GetMapping("/packageInfo")
    public DraftOrderPackageDetailVO packageInfo(@RequestParam Long orderId) {
        return draftOrderManager.packageInfo(orderId);
    }
}
