package com.tang.plugin.controller.order;

import com.tang.plugin.domain.dto.order.BindingBackfillResult;
import com.tang.plugin.service.order.OrderLineBackfillService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal integration endpoints for manual binding backfill (NOT a workbench UI).
 * Explicit capability — never auto-triggered by binding creation.
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/order/backfill")
public class OrderLineBackfillAdminController {

    @Resource
    private OrderLineBackfillService orderLineBackfillService;

    @PostMapping("/line")
    public BindingBackfillResult backfillByLine(@RequestParam String shopName, @RequestParam String lineId) {
        return orderLineBackfillService.backfillByLine(shopName, lineId);
    }

    @PostMapping("/variant")
    public BindingBackfillResult backfillByVariant(@RequestParam String shopName, @RequestParam String variantGid) {
        return orderLineBackfillService.backfillByVariant(shopName, variantGid);
    }
}
