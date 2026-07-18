package com.tang.plugin.controller.procurement;

import com.tang.plugin.domain.dto.procurement.ProcurementAckResult;
import com.tang.plugin.domain.dto.procurement.ProcurementPullResult;
import com.tang.plugin.service.procurement.ProcurementOutboxDeliveryService;
import jakarta.annotation.Resource;
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

    @PostMapping("/pull")
    public ProcurementPullResult pull(@RequestParam String shopName,
                                      @RequestParam(required = false) Integer limit) {
        return procurementOutboxDeliveryService.pull(shopName, limit);
    }

    @PostMapping("/ack")
    public ProcurementAckResult ackByLine(@RequestParam String shopName, @RequestParam String lineId) {
        return procurementOutboxDeliveryService.ackByLine(shopName, lineId);
    }

    @PostMapping("/ack-by-task")
    public ProcurementAckResult ackByTaskId(@RequestParam Long taskId) {
        return procurementOutboxDeliveryService.ackByTaskId(taskId);
    }
}
