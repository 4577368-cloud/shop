package com.tang.plugin.controller.procurement;

import com.tang.plugin.domain.dto.procurement.ProcurementAnomalyEntry;
import com.tang.plugin.domain.dto.procurement.ProcurementChainSummary;
import com.tang.plugin.domain.dto.procurement.ProcurementChainView;
import com.tang.plugin.enums.procurement.ProcurementChainAnomaly;
import com.tang.plugin.enums.procurement.ProcurementTaskDeliveryStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskStatus;
import com.tang.plugin.service.procurement.ProcurementOpsQueryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only operations/audit endpoints for the procurement chain (NOT a workbench UI).
 * No writes; never changes delivery_status / consumption_status.
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/procurement/ops")
public class ProcurementOpsController {

    @Resource
    private ProcurementOpsQueryService opsQueryService;

    @GetMapping("/chain/by-task")
    public ProcurementChainView chainByTask(@RequestParam String shopName, @RequestParam Long taskId) {
        return opsQueryService.chainByTask(shopName, taskId);
    }

    @GetMapping("/chain/by-line")
    public ProcurementChainView chainByLine(@RequestParam String shopName, @RequestParam String lineId) {
        return opsQueryService.chainByLine(shopName, lineId);
    }

    @GetMapping("/chain/by-shop")
    public List<ProcurementChainView> chainByShop(@RequestParam String shopName,
                                                  @RequestParam(required = false) ProcurementTaskStatus taskStatus,
                                                  @RequestParam(required = false)
                                                  ProcurementTaskDeliveryStatus deliveryStatus,
                                                  @RequestParam(required = false) Integer limit) {
        return opsQueryService.chainByShop(shopName, taskStatus, deliveryStatus, limit);
    }

    @GetMapping("/anomalies")
    public List<ProcurementAnomalyEntry> anomalies(@RequestParam String shopName,
                                                   @RequestParam(required = false) ProcurementChainAnomaly type,
                                                   @RequestParam(required = false) Long staleMinutes,
                                                   @RequestParam(required = false, defaultValue = "false")
                                                   boolean includeInfo,
                                                   @RequestParam(required = false) Integer limit) {
        return opsQueryService.anomalies(shopName, type, staleMinutes, includeInfo, limit);
    }

    @GetMapping("/summary")
    public ProcurementChainSummary summary(@RequestParam String shopName) {
        return opsQueryService.summary(shopName);
    }
}
