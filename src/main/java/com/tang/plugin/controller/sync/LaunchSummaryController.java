package com.tang.plugin.controller.sync;

import com.tang.plugin.domain.dto.sync.LaunchSummaryBundleVO;
import com.tang.plugin.service.sync.LaunchSummaryService;
import com.tang.plugin.service.user.ShopAccessGuard;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workbench sync page — aggregated launch inputs ({@code GET /api/plugin/sync/launch-summary}).
 */
@RestController
@RequestMapping("/api/plugin/sync")
public class LaunchSummaryController {

    @Resource
    private LaunchSummaryService launchSummaryService;
    @Resource
    private ShopAccessGuard shopAccessGuard;

    @GetMapping("/launch-summary")
    public LaunchSummaryBundleVO launchSummary(HttpServletRequest request,
                                               @RequestParam String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return launchSummaryService.aggregate(shopName);
    }
}
