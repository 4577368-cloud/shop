package com.tang.plugin.controller.order;

import com.tang.plugin.domain.entity.order.ThirdPlatformOrder;
import com.tang.plugin.service.order.OrderHeaderQueryService;
import com.tang.plugin.service.user.ShopAccessGuard;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal integration endpoints for persisted order headers (NOT a workbench UI). Read-only.
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/order/header")
public class OrderHeaderAdminController {

    @Resource
    private OrderHeaderQueryService orderHeaderQueryService;
    @Resource
    private ShopAccessGuard shopAccessGuard;

    @GetMapping("/get")
    public ThirdPlatformOrder findByOuterOrderId(HttpServletRequest request,
                                                 @RequestParam String shopName,
                                                 @RequestParam String outerOrderId) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return orderHeaderQueryService.findByOuterOrderId(shopName, outerOrderId).orElse(null);
    }

    @GetMapping("/list")
    public List<ThirdPlatformOrder> listByShop(HttpServletRequest request,
                                               @RequestParam String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return orderHeaderQueryService.listByShop(shopName);
    }
}
