package com.tang.plugin.service.marketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tang.plugin.config.PipispyProperties;
import com.tang.plugin.dto.marketing.MarketingDtos.MarketingDataResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-side pipispy proxy. Injects API key; browsers never see it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipispyClient {

    private final PipispyProperties props;
    private final ObjectMapper objectMapper;

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
            String raw = restClient().post()
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
            String raw = restClient().post()
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

    private RestClient restClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        return RestClient.builder().requestFactory(factory).build();
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
}
