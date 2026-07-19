package com.tang.plugin.service.match.sku;

import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured, fixed-format codec for the S1-b1 SKU auto-alignment {@code match_reason} audit column.
 * Sibling of {@link com.tang.plugin.service.match.image.ImageMatchReason} but for RULE/AI variant↔SKU
 * bindings: a pipe-delimited ordered {@code key=value} string carrying the alignment context.
 *
 * <p>Format: {@code algo=<RULE|AI>|score=<0.0-1.0>|sku=<offerSkuId>|spec=<specLabel>|url=<detailUrl>}.
 * Keys are always emitted in this order; reserved characters {@code | = \n \r} are stripped from values.
 * The whole string is capped to {@link #MAX_LEN} (the {@code match_reason VARCHAR(1024)} width) by
 * trimming {@code spec} then {@code url}.
 */
public final class SkuMatchReason {

    /** Matches {@code match_reason VARCHAR(1024)}. */
    public static final int MAX_LEN = 1024;

    private SkuMatchReason() {
    }

    public static String encode(String algo, double score, String offerSkuId, String specLabel, String detailUrl) {
        String a = StringUtils.defaultIfBlank(sanitize(algo), "RULE");
        String s = String.format("%.4f", Math.max(0d, Math.min(1d, score)));
        String sku = sanitize(offerSkuId);
        String spec = sanitize(specLabel);
        String url = sanitize(detailUrl);
        String head = "algo=" + a + "|score=" + s + "|sku=" + sku + "|spec=";
        String mid = "|url=";
        int budget = MAX_LEN - head.length() - mid.length();
        if (budget < 0) {
            return StringUtils.left(head, MAX_LEN);
        }
        // spec gets up to half the remaining budget, url takes the rest.
        int specBudget = Math.min(spec.length(), Math.max(0, budget / 2));
        spec = spec.substring(0, specBudget);
        int urlBudget = budget - spec.length();
        if (url.length() > urlBudget) {
            url = url.substring(0, Math.max(0, urlBudget));
        }
        return head + spec + mid + url;
    }

    public static Decoded decode(String reason) {
        Map<String, String> map = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(reason)) {
            for (String part : reason.split("\\|", -1)) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    map.put(part.substring(0, eq), part.substring(eq + 1));
                }
            }
        }
        return new Decoded(
                emptyToNull(map.get("algo")),
                emptyToNull(map.get("sku")),
                emptyToNull(map.get("spec")),
                emptyToNull(map.get("url")));
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[|=\\r\\n]", " ").trim();
    }

    private static String emptyToNull(String v) {
        return StringUtils.isBlank(v) ? null : v;
    }

    /** Decoded reason parts (any may be null). */
    public record Decoded(String algo, String offerSkuId, String specLabel, String detailUrl) {
    }
}
