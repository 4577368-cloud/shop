package com.tang.plugin.controller.order;

import com.tang.plugin.domain.entity.order.ThirdPlatformOrder;
import com.tang.plugin.service.order.OrderHeaderQueryService;
import jakarta.annotation.Resource;
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

    @GetMapping("/get")
    public ThirdPlatformOrder findByOuterOrderId(@RequestParam String shopName,
                                                 @RequestParam String outerOrderId) {
        return orderHeaderQueryService.findByOuterOrderId(shopName, outerOrderId).orElse(null);
    }

    @GetMapping("/list")
    public List<ThirdPlatformOrder> listByShop(@RequestParam String shopName) {
        return orderHeaderQueryService.listByShop(shopName);
    }
}
