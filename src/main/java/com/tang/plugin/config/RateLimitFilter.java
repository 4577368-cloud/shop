package com.tang.plugin.config;

import com.tang.plugin.config.JwtAuthProperties.RateLimit;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory per-IP rate limiter for sensitive auth endpoints.
 *
 * <p>Protected endpoints (when rate-limit.enabled=true):
 * <ul>
 *   <li>{@code POST /api/plugin/auth/login} — loginMaxAttempts / loginWindowSeconds</li>
 *   <li>{@code POST /api/plugin/auth/register} — registerMaxAttempts / registerWindowSeconds</li>
 *   <li>{@code POST /api/plugin/auth/forgot-password} — forgotPasswordMaxAttempts / forgotPasswordWindowSeconds</li>
 * </ul>
 *
 * <p>Implementation: sliding window via a map of {@code key -> [count, windowStartMillis]}.
 * Not distributed — each instance tracks its own counters. Adequate for a single-instance
 * deployment; for multi-instance, switch to Redis bucket4j.
 *
 * <p>Key format: {@code <endpoint>:<clientIp>}. Each endpoint has its own counter so logging
 * in to one account doesn't burn the register quota.
 */
@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    @Resource
    private JwtAuthProperties properties;

    // key = "endpoint:ip" → [count, windowStartMillis]
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        RateLimit cfg = properties.getRateLimit();
        if (!cfg.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String endpoint = endpointFor(request);
        if (endpoint == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = extractClientIp(request);
        String key = endpoint + ":" + ip;
        int maxAttempts;
        long windowMillis;
        switch (endpoint) {
            case "login" -> {
                maxAttempts = cfg.getLoginMaxAttempts();
                windowMillis = cfg.getLoginWindowSeconds() * 1000L;
            }
            case "register" -> {
                maxAttempts = cfg.getRegisterMaxAttempts();
                windowMillis = cfg.getRegisterWindowSeconds() * 1000L;
            }
            case "forgot" -> {
                maxAttempts = cfg.getForgotPasswordMaxAttempts();
                windowMillis = cfg.getForgotPasswordWindowSeconds() * 1000L;
            }
            default -> {
                filterChain.doFilter(request, response);
                return;
            }
        }

        if (isRateLimited(key, maxAttempts, windowMillis)) {
            log.warn("Rate limit exceeded: endpoint={} ip={} max={}", endpoint, ip, maxAttempts);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", String.valueOf(cfg.getLoginWindowSeconds()));
            response.getWriter().write(
                    "{\"status\":\"ERROR\",\"message\":\"Too many attempts. Please try again later.\",\"code\":\"RATE_LIMITED\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /** Returns the logical endpoint name for rate-limiting, or null if the request isn't limited. */
    private String endpointFor(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method)) return null;
        if ("/api/plugin/auth/login".equals(uri)) return "login";
        if ("/api/plugin/auth/register".equals(uri)) return "register";
        if ("/api/plugin/auth/forgot-password".equals(uri)) return "forgot";
        return null;
    }

    /**
     * Atomically increments the counter and returns true if the limit is exceeded.
     * Sliding window: if the current time is past windowStart + windowMillis, reset the counter.
     */
    private boolean isRateLimited(String key, int maxAttempts, long windowMillis) {
        long now = System.currentTimeMillis();
        WindowCounter counter = counters.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStartMillis >= windowMillis) {
                return new WindowCounter(1, now);
            }
            existing.count.incrementAndGet();
            return existing;
        });
        return counter.count.get() > maxAttempts;
    }

    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isEmpty()) return real.trim();
        return request.getRemoteAddr();
    }

    private static class WindowCounter {
        final AtomicInteger count;
        final long windowStartMillis;

        WindowCounter(int initialCount, long windowStartMillis) {
            this.count = new AtomicInteger(initialCount);
            this.windowStartMillis = windowStartMillis;
        }
    }
}
