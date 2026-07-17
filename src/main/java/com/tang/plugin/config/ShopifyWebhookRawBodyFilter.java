package com.tang.plugin.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;

/**
 * Caches raw body for Shopify webhook HMAC verification.
 */
@Component
public class ShopifyWebhookRawBodyFilter extends OncePerRequestFilter implements Ordered {

    public static final String WEBHOOK_PATH = "/api/plugin/shopify/webhook";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !WEBHOOK_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper wrapper = request instanceof ContentCachingRequestWrapper cached
                ? cached
                : new ContentCachingRequestWrapper(request);
        filterChain.doFilter(wrapper, response);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
