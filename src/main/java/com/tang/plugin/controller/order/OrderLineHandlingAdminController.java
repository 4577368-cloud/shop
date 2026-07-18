package com.tang.plugin.controller.order;

import com.tang.plugin.domain.dto.order.UnboundOrderLineQuery;
import com.tang.plugin.domain.entity.order.ThirdPlatformOrderLine;
import com.tang.plugin.enums.order.OrderLineHandlingStatus;
import com.tang.plugin.service.order.OrderLineHandlingQueryService;
import com.tang.plugin.service.order.OrderLineHandlingService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal integration endpoints for UNBOUND order-line handling (NOT a workbench UI).
 * Minimal surface for local / 联调 verification of the P1 handling flow.
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/order/handling")
public class OrderLineHandlingAdminController {

    @Resource
    private OrderLineHandlingService orderLineHandlingService;
    @Resource
    private OrderLineHandlingQueryService orderLineHandlingQueryService;

    @PostMapping("/ignore")
    public Map<String, Object> ignore(@RequestParam String shopName,
                                      @RequestParam String lineId,
                                      @RequestParam(required = false) String note) {
        orderLineHandlingService.markIgnored(shopName, lineId, note);
        return ok();
    }

    @PostMapping("/resolve")
    public Map<String, Object> resolve(@RequestParam String shopName,
                                       @RequestParam String lineId,
                                       @RequestParam(required = false) String note) {
        orderLineHandlingService.markResolved(shopName, lineId, note);
        return ok();
    }

    @PostMapping("/reopen")
    public Map<String, Object> reopen(@RequestParam String shopName, @RequestParam String lineId) {
        orderLineHandlingService.reopen(shopName, lineId);
        return ok();
    }

    @GetMapping("/unbound")
    public List<ThirdPlatformOrderLine> listUnbound(@RequestParam String shopName,
                                                    @RequestParam(required = false) OrderLineHandlingStatus handlingStatus,
                                                    @RequestParam(required = false) String outerOrderId) {
        UnboundOrderLineQuery query = new UnboundOrderLineQuery()
                .setShopName(shopName)
                .setHandlingStatus(handlingStatus)
                .setOuterOrderId(outerOrderId);
        return orderLineHandlingQueryService.listUnbound(query);
    }

    @GetMapping("/count")
    public Map<String, Integer> count(@RequestParam String shopName) {
        return orderLineHandlingQueryService.countUnboundByHandling(shopName);
    }

    private Map<String, Object> ok() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        return body;
    }
}
