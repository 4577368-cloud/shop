package com.tang.plugin.service.match.image;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vision LLM helper (A3-2a tier 3). Given a product image, returns a short Chinese subject word to use
 * as a 1688 image-search correction query. OpenAI-compatible {@code /chat/completions} (multimodal),
 * configured via env {@code LLM_MODEL_BASE_URL} / {@code LLM_MODEL_API_KEY} / {@code LLM_MODEL_MODEL_ID}.
 *
 * <p>Best-effort by contract: any failure/timeout/misconfiguration returns {@code null} (never throws),
 * so the caller degrades gracefully. Its read timeout is intentionally shorter than the 1688 image-search
 * main chain. Subjects are cached in-memory for a short TTL keyed by image URL.
 */
@Slf4j
@Component
public class LlmVisionClient {

    static final String SUBJECT_PROMPT =
            "用一个简短的中文商品主体词描述这张图片里的商品，只返回词本身，不要标点和解释。";
    private static final int MAX_SUBJECT_LEN = 16;
    private static final long CACHE_TTL_MS = 30L * 60 * 1000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(8);

    private final RestClient restClient;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public LlmVisionClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** True when LLM env is configured and the tier is not explicitly disabled. */
    public boolean isEnabled() {
        if ("false".equalsIgnoreCase(StringUtils.trimToEmpty(System.getenv("IMAGE_SEARCH_LLM_ENABLED")))) {
            return false;
        }
        return StringUtils.isNoneBlank(resolveBaseUrl(), resolveModel());
    }

    /**
     * Describe the dominant product in the image as a short Chinese subject word.
     *
     * @return the subject, or {@code null} on any failure/timeout/misconfiguration (never throws).
     */
    public String describeSubject(String imageUrl) {
        if (StringUtils.isBlank(imageUrl) || !isEnabled()) {
            return null;
        }
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(imageUrl);
        if (cached != null && cached.expireAt > now) {
            return cached.subject;
        }
        cache.entrySet().removeIf(e -> e.getValue().expireAt <= System.currentTimeMillis());

        try {
            String url = StringUtils.removeEnd(resolveBaseUrl(), "/") + "/chat/completions";
            String body = buildRequestBody(resolveModel(), SUBJECT_PROMPT, imageUrl);
            String raw = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        String key = resolveApiKey();
                        if (StringUtils.isNotBlank(key)) {
                            h.add("Authorization", "Bearer " + key);
                        }
                    })
                    .body(body)
                    .retrieve()
                    .body(String.class);
            String subject = parseSubject(raw);
            // Cache even nulls briefly to avoid hammering a flaky LLM for the same image.
            cache.put(imageUrl, new CacheEntry(subject, System.currentTimeMillis() + CACHE_TTL_MS));
            return subject;
        } catch (Exception e) {
            log.warn("LLM vision subject failed imageUrl={} err={}", imageUrl, e.toString());
            return null;
        }
    }

    /** Build the OpenAI-compatible multimodal chat body (pure, testable). */
    static String buildRequestBody(String model, String prompt, String imageUrl) {
        JSONObject text = new JSONObject();
        text.put("type", "text");
        text.put("text", prompt);
        JSONObject image = new JSONObject();
        image.put("type", "image_url");
        JSONObject imageUrlObj = new JSONObject();
        imageUrlObj.put("url", imageUrl);
        image.put("image_url", imageUrlObj);
        JSONArray content = new JSONArray();
        content.add(text);
        content.add(image);
        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", content);
        JSONArray messages = new JSONArray();
        messages.add(message);
        JSONObject root = new JSONObject();
        root.put("model", model);
        root.put("messages", messages);
        root.put("max_tokens", 20);
        root.put("temperature", 0);
        return root.toJSONString();
    }

    /** Extract choices[0].message.content and sanitize it into a subject word (pure, testable). */
    static String parseSubject(String rawResponse) {
        if (StringUtils.isBlank(rawResponse)) {
            return null;
        }
        JSONObject root = JSON.parseObject(rawResponse);
        if (root == null) {
            return null;
        }
        JSONArray choices = root.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        JSONObject first = choices.getJSONObject(0);
        JSONObject message = first == null ? null : first.getJSONObject("message");
        String content = message == null ? null : message.getString("content");
        return sanitizeSubject(content);
    }

    /** First line, stripped of quotes/punctuation/whitespace, capped in length; null when empty (pure). */
    static String sanitizeSubject(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String s = raw.trim();
        int nl = s.indexOf('\n');
        if (nl >= 0) {
            s = s.substring(0, nl);
        }
        // strip Unicode punctuation (incl. full-width quotes 「“ ”」、。), symbols, and whitespace.
        s = s.replaceAll("[\\p{P}\\p{S}\\s]", "");
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > MAX_SUBJECT_LEN ? s.substring(0, MAX_SUBJECT_LEN) : s;
    }

    private static String resolveBaseUrl() {
        return System.getenv("LLM_MODEL_BASE_URL");
    }

    private static String resolveApiKey() {
        return System.getenv("LLM_MODEL_API_KEY");
    }

    private static String resolveModel() {
        return System.getenv("LLM_MODEL_MODEL_ID");
    }

    private record CacheEntry(String subject, long expireAt) {
    }
}
