package com.tang.plugin.controller.procurement;

import com.tang.plugin.domain.dto.procurement.ProcurementExecutionResult;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementExecution;
import com.tang.plugin.enums.procurement.ProcurementExecutionStatus;
import com.tang.plugin.service.procurement.ProcurementExecutionStubService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal execution stub endpoints (NOT a workbench UI, NOT real procurement execution).
 * create requires an accepted task; complete is orthogonal to task_status.
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/procurement/execution")
public class ProcurementExecutionController {

    @Resource
    private ProcurementExecutionStubService executionStubService;

    @PostMapping("/create")
    public ProcurementExecutionResult create(@RequestParam String shopName,
                                             @RequestParam Long taskId,
                                             @RequestParam(required = false) String consumerId,
                                             @RequestParam(required = false) String note) {
        return executionStubService.createFromAcceptedTask(shopName, taskId, consumerId, note);
    }

    @PostMapping("/complete")
    public ProcurementExecutionResult complete(@RequestParam String shopName,
                                               @RequestParam Long taskId,
                                               @RequestParam(required = false) String note) {
        return executionStubService.completeStub(shopName, taskId, note);
    }

    @GetMapping("/by-task")
    public ThirdPlatformProcurementExecution byTask(@RequestParam String shopName, @RequestParam Long taskId) {
        return executionStubService.getByTask(shopName, taskId);
    }

    @GetMapping("/by-status")
    public List<ThirdPlatformProcurementExecution> byStatus(@RequestParam String shopName,
                                                            @RequestParam ProcurementExecutionStatus status) {
        return executionStubService.listByStatus(shopName, status);
    }
}
