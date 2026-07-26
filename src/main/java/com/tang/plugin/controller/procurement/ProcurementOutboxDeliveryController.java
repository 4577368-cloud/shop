package com.tang.plugin.controller.procurement;

import com.tang.plugin.domain.dto.procurement.ProcurementAckResult;
import com.tang.plugin.domain.dto.procurement.ProcurementPullResult;
import com.tang.plugin.service.procurement.ProcurementOutboxDeliveryService;
import com.tang.plugin.service.user.ShopAccessGuard;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal outbox delivery endpoints for procurement tasks (NOT a workbench UI).
 * pull is read-only + observational marker; ack (by line or by taskId) is the only DELIVERED entry.
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/procurement/outbox")
public class ProcurementOutboxDeliveryController {

    @Resource
    private ProcurementOutboxDeliveryService procurementOutboxDeliveryService;
    @Resource
    private ShopAccessGuard shopAccessGuard;

    @PostMapping("/pull")
    public ProcurementPullResult pull(HttpServletRequest request,
                                      @RequestParam String shopName,
                                      @RequestParam(required = false) Integer limit) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return procurementOutboxDeliveryService.pull(shopName, limit);
    }

    @PostMapping("/ack")
    public ProcurementAckResult ackByLine(HttpServletRequest request,
                                         @RequestParam String shopName, @RequestParam String lineId) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return procurementOutboxDeliveryService.ackByLine(shopName, lineId);
    }

    @PostMapping("/ack-by-task")
    public ProcurementAckResult ackByTaskId(@RequestParam Long taskId) {
        return procurementOutboxDeliveryService.ackByTaskId(taskId);
    }
}
