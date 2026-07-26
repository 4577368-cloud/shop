package com.tang.plugin.controller.procurement;

import com.tang.plugin.domain.dto.procurement.ProcurementConsumeResult;
import com.tang.plugin.domain.dto.procurement.ProcurementConsumptionView;
import com.tang.plugin.enums.procurement.ProcurementConsumptionStatus;
import com.tang.plugin.service.procurement.ProcurementConsumerIntegrationService;
import com.tang.plugin.service.user.ShopAccessGuard;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
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
    @Resource
    private ShopAccessGuard shopAccessGuard;

    @PostMapping("/receive")
    public ProcurementConsumeResult receive(HttpServletRequest request,
                                            @RequestParam String shopName,
                                            @RequestParam Long taskId,
                                            @RequestParam String consumerId,
                                            @RequestParam(required = false) String consumerRef) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return consumerIntegrationService.receive(shopName, taskId, consumerId, consumerRef);
    }

    @PostMapping("/accept")
    public ProcurementConsumeResult accept(HttpServletRequest request,
                                           @RequestParam String shopName,
                                           @RequestParam Long taskId,
                                           @RequestParam String consumerId,
                                           @RequestParam(required = false) String consumerRef) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return consumerIntegrationService.accept(shopName, taskId, consumerId, consumerRef);
    }

    @GetMapping("/by-task")
    public List<ProcurementConsumptionView> listByTask(HttpServletRequest request,
                                                       @RequestParam String shopName,
                                                       @RequestParam Long taskId) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return consumerIntegrationService.listByTask(shopName, taskId);
    }

    @GetMapping("/by-status")
    public List<ProcurementConsumptionView> listByStatus(HttpServletRequest request,
                                                         @RequestParam String shopName,
                                                         @RequestParam ProcurementConsumptionStatus status) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return consumerIntegrationService.listByStatus(shopName, status);
    }
}
