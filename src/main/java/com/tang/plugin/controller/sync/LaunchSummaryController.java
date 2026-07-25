package com.tang.plugin.controller.sync;

import com.tang.plugin.domain.dto.sync.LaunchSummaryBundleVO;
import com.tang.plugin.service.sync.LaunchSummaryService;
import jakarta.annotation.Resource;
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

    @GetMapping("/launch-summary")
    public LaunchSummaryBundleVO launchSummary(@RequestParam String shopName) {
        return launchSummaryService.aggregate(shopName);
    }
}
