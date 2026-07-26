package com.tang.plugin.controller.procurement;

import com.tang.plugin.domain.dto.procurement.ProcurementTaskCreateResult;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementTask;
import com.tang.plugin.enums.procurement.ProcurementTaskStatus;
import com.tang.plugin.service.procurement.ProcurementTaskQueryService;
import com.tang.plugin.service.procurement.ProcurementTaskService;
import com.tang.plugin.service.user.ShopAccessGuard;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
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
 * Internal integration endpoints for procurement task creation (outbox; NOT a workbench UI).
 * Explicit capability — never auto-triggered.
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/procurement/task")
public class ProcurementTaskAdminController {

    @Resource
    private ProcurementTaskService procurementTaskService;
    @Resource
    private ProcurementTaskQueryService procurementTaskQueryService;
    @Resource
    private ShopAccessGuard shopAccessGuard;

    @PostMapping("/create-from-line")
    public Map<String, Object> createFromLine(HttpServletRequest request,
                                              @RequestParam String shopName, @RequestParam String lineId) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        Long id = procurementTaskService.createFromOrderLine(shopName, lineId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("taskId", id);
        return body;
    }

    @PostMapping("/create-from-order")
    public ProcurementTaskCreateResult createFromOrder(HttpServletRequest request,
                                                       @RequestParam String shopName,
                                                       @RequestParam String outerOrderId) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return procurementTaskService.createFromOrder(shopName, outerOrderId);
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel(HttpServletRequest request,
                                       @RequestParam String shopName, @RequestParam String lineId) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        procurementTaskService.cancelByLine(shopName, lineId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        return body;
    }

    @GetMapping("/by-order")
    public List<ThirdPlatformProcurementTask> listByOrder(HttpServletRequest request,
                                                          @RequestParam String shopName,
                                                          @RequestParam String outerOrderId) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return procurementTaskQueryService.listByOrder(shopName, outerOrderId);
    }

    @GetMapping("/by-status")
    public List<ThirdPlatformProcurementTask> listByStatus(HttpServletRequest request,
                                                           @RequestParam String shopName,
                                                           @RequestParam ProcurementTaskStatus status) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return procurementTaskQueryService.listByStatus(shopName, status);
    }
}
