package com.tang.plugin.controller.order;

import com.tang.plugin.domain.dto.order.OrderBindingSummary;
import com.tang.plugin.domain.entity.order.ThirdPlatformOrderLine;
import com.tang.plugin.service.order.OrderLineBindingQueryService;
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
 * Internal integration endpoints for order-line binding visibility (NOT a workbench UI).
 * Read-only; minimal surface for local / 联调 verification.
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/order/binding")
public class OrderLineBindingAdminController {

    @Resource
    private OrderLineBindingQueryService orderLineBindingQueryService;
    @Resource
    private ShopAccessGuard shopAccessGuard;

    @GetMapping("/lines")
    public List<ThirdPlatformOrderLine> listByOrder(HttpServletRequest request,
                                                   @RequestParam String shopName,
                                                   @RequestParam String outerOrderId) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return orderLineBindingQueryService.listByOrder(shopName, outerOrderId);
    }

    @GetMapping("/unbound")
    public List<ThirdPlatformOrderLine> listUnbound(HttpServletRequest request,
                                                   @RequestParam String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return orderLineBindingQueryService.listUnbound(shopName);
    }

    @GetMapping("/bound")
    public List<ThirdPlatformOrderLine> listBound(HttpServletRequest request,
                                                  @RequestParam String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return orderLineBindingQueryService.listBound(shopName);
    }

    @GetMapping("/count")
    public OrderBindingSummary countByOrder(HttpServletRequest request,
                                            @RequestParam String shopName,
                                            @RequestParam String outerOrderId) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return orderLineBindingQueryService.countByOrder(shopName, outerOrderId);
    }
}
