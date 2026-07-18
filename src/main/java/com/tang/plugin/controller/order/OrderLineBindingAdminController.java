package com.tang.plugin.controller.order;

import com.tang.plugin.domain.dto.order.OrderBindingSummary;
import com.tang.plugin.domain.entity.order.ThirdPlatformOrderLine;
import com.tang.plugin.service.order.OrderLineBindingQueryService;
import jakarta.annotation.Resource;
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

    @GetMapping("/lines")
    public List<ThirdPlatformOrderLine> listByOrder(@RequestParam String shopName,
                                                    @RequestParam String outerOrderId) {
        return orderLineBindingQueryService.listByOrder(shopName, outerOrderId);
    }

    @GetMapping("/unbound")
    public List<ThirdPlatformOrderLine> listUnbound(@RequestParam String shopName) {
        return orderLineBindingQueryService.listUnbound(shopName);
    }

    @GetMapping("/bound")
    public List<ThirdPlatformOrderLine> listBound(@RequestParam String shopName) {
        return orderLineBindingQueryService.listBound(shopName);
    }

    @GetMapping("/count")
    public OrderBindingSummary countByOrder(@RequestParam String shopName,
                                            @RequestParam String outerOrderId) {
        return orderLineBindingQueryService.countByOrder(shopName, outerOrderId);
    }
}
