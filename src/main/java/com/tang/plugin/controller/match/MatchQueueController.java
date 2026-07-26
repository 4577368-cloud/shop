package com.tang.plugin.controller.match;

import com.tang.plugin.domain.dto.match.MatchJobProgressVO;
import com.tang.plugin.service.match.MatchQueueService;
import com.tang.plugin.service.user.ShopAccessGuard;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Background image-auto-match queue: start after auth sync or manual trigger; poll real progress.
 */
@RestController
@RequestMapping("/api/plugin/match/queue")
public class MatchQueueController {

    @Resource
    private MatchQueueService matchQueueService;
    @Resource
    private ShopAccessGuard shopAccessGuard;

    @PostMapping("/start")
    public MatchJobProgressVO start(
            HttpServletRequest request,
            @RequestParam String shopName,
            @RequestParam(required = false) String thirdPlatformItemId,
            @RequestParam(required = false) List<String> thirdPlatformItemIds) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        if (thirdPlatformItemIds != null && !thirdPlatformItemIds.isEmpty()) {
            return matchQueueService.startImageAutoMatch(shopName, null, thirdPlatformItemIds);
        }
        return matchQueueService.startImageAutoMatch(shopName, thirdPlatformItemId);
    }

    @GetMapping("/active")
    public MatchJobProgressVO active(HttpServletRequest request,
                                     @RequestParam String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return matchQueueService.getActiveProgress(shopName);
    }

    @GetMapping("/{jobId}")
    public MatchJobProgressVO get(@PathVariable Long jobId) {
        return matchQueueService.getProgress(jobId);
    }
}
