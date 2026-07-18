package com.tang.plugin.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Ultra-light liveness probe for external keep-alive (e.g. cron-job.org / UptimeRobot) to prevent
 * free-tier spin-down. Intentionally does NO database or dependency checks so it stays fast and
 * cannot fail on a slow/unavailable DB — use {@code /api/plugin/health} for real health with
 * persistence status. Public endpoint (outside the internal-token guard).
 */
@RestController
public class PingController {

    @GetMapping("/api/plugin/ping")
    public Map<String, Object> ping() {
        return Map.of("status", "ok", "ts", System.currentTimeMillis());
    }
}
