package com.tang.plugin.controller.procurement;

import com.tang.plugin.domain.dto.procurement.ProcurementConsumeResult;
import com.tang.plugin.domain.dto.procurement.ProcurementConsumptionView;
import com.tang.plugin.enums.procurement.ProcurementConsumptionStatus;
import com.tang.plugin.service.procurement.ProcurementConsumerIntegrationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal consumer integration endpoints (NOT a workbench UI).
 * receive = record receipt only; accept = record acceptance + drive outbox ack (DELIVERED).
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/procurement/consumer")
public class ProcurementConsumerController {

    @Resource
    private ProcurementConsumerIntegrationService consumerIntegrationService;

    @PostMapping("/receive")
    public ProcurementConsumeResult receive(@RequestParam String shopName,
                                            @RequestParam Long taskId,
                                            @RequestParam String consumerId,
                                            @RequestParam(required = false) String consumerRef) {
        return consumerIntegrationService.receive(shopName, taskId, consumerId, consumerRef);
    }

    @PostMapping("/accept")
    public ProcurementConsumeResult accept(@RequestParam String shopName,
                                           @RequestParam Long taskId,
                                           @RequestParam String consumerId,
                                           @RequestParam(required = false) String consumerRef) {
        return consumerIntegrationService.accept(shopName, taskId, consumerId, consumerRef);
    }

    @GetMapping("/by-task")
    public List<ProcurementConsumptionView> listByTask(@RequestParam String shopName,
                                                       @RequestParam Long taskId) {
        return consumerIntegrationService.listByTask(shopName, taskId);
    }

    @GetMapping("/by-status")
    public List<ProcurementConsumptionView> listByStatus(@RequestParam String shopName,
                                                         @RequestParam ProcurementConsumptionStatus status) {
        return consumerIntegrationService.listByStatus(shopName, status);
    }
}
