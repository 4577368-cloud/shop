package com.tang.plugin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Minimal internal token auth config for procurement endpoints.
 * Disabled by default (local/test unaffected); enabled on the prod profile via env.
 */
@Data
@Component
@ConfigurationProperties(prefix = "tang.plugin.security.internal-token")
public class InternalAuthProperties {

    /** When false, the filter is a no-op (local/test default). */
    private boolean enabled = false;

    /** Shared secret compared against the request header; injected from env, never logged. */
    private String token = "";

    /** Header carrying the shared secret. */
    private String headerName = "X-Internal-Token";
}
