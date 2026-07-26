package com.tang.plugin.controller.procurement;

import com.tang.plugin.domain.dto.procurement.ProcurementExecutionResult;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementExecution;
import com.tang.plugin.enums.procurement.ProcurementExecutionStatus;
import com.tang.plugin.service.procurement.ProcurementExecutionStubService;
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
 * Internal execution stub endpoints (NOT a workbench UI, NOT real procurement execution).
 * create requires an accepted task; complete is orthogonal to task_status.
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/procurement/execution")
public class ProcurementExecutionController {

    @Resource
    private ProcurementExecutionStubService executionStubService;
    @Resource
    private ShopAccessGuard shopAccessGuard;

    @PostMapping("/create")
    public ProcurementExecutionResult create(HttpServletRequest request,
                                             @RequestParam String shopName,
                                             @RequestParam Long taskId,
                                             @RequestParam(required = false) String consumerId,
                                             @RequestParam(required = false) String note) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return executionStubService.createFromAcceptedTask(shopName, taskId, consumerId, note);
    }

    @PostMapping("/complete")
    public ProcurementExecutionResult complete(HttpServletRequest request,
                                               @RequestParam String shopName,
                                               @RequestParam Long taskId,
                                               @RequestParam(required = false) String note) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return executionStubService.completeStub(shopName, taskId, note);
    }

    @GetMapping("/by-task")
    public ThirdPlatformProcurementExecution byTask(HttpServletRequest request,
                                                    @RequestParam String shopName, @RequestParam Long taskId) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return executionStubService.getByTask(shopName, taskId);
    }

    @GetMapping("/by-status")
    public List<ThirdPlatformProcurementExecution> byStatus(HttpServletRequest request,
                                                            @RequestParam String shopName,
                                                            @RequestParam ProcurementExecutionStatus status) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return executionStubService.listByStatus(shopName, status);
    }
}
