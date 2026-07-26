package com.tang.plugin.service.marketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tang.plugin.config.PipispyProperties;
import com.tang.plugin.dto.marketing.MarketingDtos.DossierRequestItem;
import com.tang.plugin.dto.marketing.MarketingDtos.MarketingDataResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.AbstractMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Server-side pipispy proxy. Injects API key; browsers never see it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipispyClient {

    private final PipispyProperties props;
    private final ObjectMapper objectMapper;
    /** Bounded executor for fan-out to prevent ForkJoinPool common-pool exhaustion. */
    private final ExecutorService fanOutExecutor = Executors.newFixedThreadPool(16,
            r -> {
                Thread t = new Thread(r, "pipispy-fanout-" + System.nanoTime());
                t.setDaemon(true);
                return t;
            });
    private volatile RestClient restClient;

    public MarketingDataResponse postData(String uri, Map<String, Object> params) {
        if (!props.isConfigured()) {
            return error(503, "PIPIADS API key not configured on server");
        }
        if (StringUtils.isBlank(uri)) {
            return error(400, "uri is required");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", props.getApiKey().trim());
        body.put("uri", uri.trim());
        body.put("params", params == null ? Map.of() : params);

        try {
            String raw = getRestClient().post()
                    .uri(props.getDataUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return mapEnvelope(raw);
        } catch (RestClientException e) {
            log.error("pipispy data call failed uri={}", uri, e);
            return error(502, "pipispy request failed: " + e.getMessage());
        }
    }

    public MarketingDataResponse fetchCreditsBalance() {
        if (!props.isConfigured()) {
            return error(503, "PIPIADS API key not configured on server");
        }
        Map<String, Object> body = Map.of("key", props.getApiKey().trim());
        try {
            String raw = getRestClient().post()
                    .uri(props.getCreditsUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return mapEnvelope(raw);
        } catch (RestClientException e) {
            log.error("pipispy credits-balance failed", e);
            return error(502, "pipispy credits-balance failed: " + e.getMessage());
        }
    }

    /**
     * Server-side fan-out: execute a batch of pipispy calls in parallel and return them keyed by tag.
     * The API key is injected server-side per call (browsers never see it). Each call is billed by
     * pipispy independently; the sum of {@code consumed_credits} is reported in the response.
     */
    public Map<String, MarketingDataResponse> fanOut(List<DossierRequestItem> items) {
        Map<String, MarketingDataResponse> out = new LinkedHashMap<>();
        if (items == null || items.isEmpty()) {
            return out;
        }
        if (!props.isConfigured()) {
            MarketingDataResponse err = error(503, "PIPIADS API key not configured on server");
            for (DossierRequestItem item : items) {
                out.put(tagOf(item), err);
            }
            return out;
        }
        List<CompletableFuture<Map.Entry<String, MarketingDataResponse>>> futures = items.stream()
                .map(item -> CompletableFuture.supplyAsync(() -> {
                    String tag = tagOf(item);
                    MarketingDataResponse r = postData(item.uri(), item.params() == null ? Map.of() : item.params());
                    Map.Entry<String, MarketingDataResponse> entry = new AbstractMap.SimpleEntry<>(tag, r);
                    return entry;
                }, fanOutExecutor))
                .collect(Collectors.toList());
        for (CompletableFuture<Map.Entry<String, MarketingDataResponse>> f : futures) {
            try {
                Map.Entry<String, MarketingDataResponse> e = f.join();
                out.put(e.getKey(), e.getValue());
            } catch (Exception ex) {
                log.error("pipispy fan-out join failed", ex);
            }
        }
        return out;
    }

    private static String tagOf(DossierRequestItem item) {
        return StringUtils.isNotBlank(item.tag()) ? item.tag() : item.uri();
    }

    private RestClient getRestClient() {
        if (restClient == null) {
            synchronized (this) {
                if (restClient == null) {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(props.getConnectTimeoutMs());
                    factory.setReadTimeout(props.getReadTimeoutMs());
                    restClient = RestClient.builder().requestFactory(factory).build();
                }
            }
        }
        return restClient;
    }

    private MarketingDataResponse mapEnvelope(String raw) {
        if (StringUtils.isBlank(raw)) {
            return error(502, "empty pipispy response");
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            Integer remaining = intOrNull(root.get("remaining_credits"));
            Integer consumed = intOrNull(root.get("consumed_credits"));
            Integer code = intOrNull(root.get("code"));
            String msg = textOrNull(root.get("msg"));
            if (msg == null) {
                msg = textOrNull(root.get("message"));
            }
            JsonNode data = root.get("data");
            if (data == null || data.isNull()) {
                data = root;
            }
            boolean ok = code == null || code == 0 || code == 200;
            return new MarketingDataResponse(ok, "pipispy", data, consumed, remaining, code, msg);
        } catch (Exception e) {
            log.error("pipispy response parse failed", e);
            return error(502, "invalid pipispy JSON");
        }
    }

    private MarketingDataResponse error(int httpHint, String message) {
        return new MarketingDataResponse(false, "pipispy", null, null, null, httpHint, message);
    }

    private static Integer intOrNull(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isInt() || n.isLong()) return n.intValue();
        if (n.isTextual()) {
            try {
                return Integer.parseInt(n.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isNull()) return null;
        return n.asText(null);
    }

    @PreDestroy
    public void shutdown() {
        fanOutExecutor.shutdown();
    }
}
