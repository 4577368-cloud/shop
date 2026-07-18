package com.tang.plugin.config;

import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Minimal shared-token guard for internal procurement endpoints. Only enforces when enabled
 * (prod profile). Scope is limited to {@code /api/plugin/procurement/**}; health, Shopify OAuth
 * (install/callback) and webhook paths are intentionally left open. Read-only guard: no state change.
 */
@Slf4j
@Component
public class InternalTokenAuthFilter extends OncePerRequestFilter {

    private static final String PROTECTED_PREFIX = "/api/plugin/procurement/";

    @Resource
    private InternalAuthProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.isEnabled() || !isProtected(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(properties.getHeaderName());
        String expected = properties.getToken();
        if (StringUtils.isBlank(expected) || !StringUtils.equals(provided, expected)) {
            log.warn("Rejected internal request (invalid token) method={} uri={}",
                    request.getMethod(), request.getRequestURI());
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"status\":\"ERROR\",\"message\":\"Unauthorized: missing or invalid internal token\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isProtected(HttpServletRequest request) {
        return request.getRequestURI().startsWith(PROTECTED_PREFIX);
    }
}
