package com.tang.plugin.service.match.image;

import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured, fixed-format codec for the image-match {@code match_reason} audit column (A3-2b).
 * Not free text: a pipe-delimited ordered {@code key=value} string carrying the A3-2a resolution
 * context so the binding stays auditable and machine-parseable.
 *
 * <p>Format: {@code img=<SHOPIFY|ORIGINAL>|qs=<NONE|TITLE|LLM>|q=<appliedQuery>|url=<detailUrl>}.
 * Keys are always emitted in this order; empty values are still emitted (empty tail) so positions are
 * stable. Reserved characters {@code | = \n \r} are stripped from values. The whole string is capped to
 * {@link #MAX_LEN} (the {@code match_reason VARCHAR(1024)} column width) by trimming the (longest) url.
 */
public final class ImageMatchReason {

    /** Matches {@code match_reason VARCHAR(1024)}. */
    public static final int MAX_LEN = 1024;

    private ImageMatchReason() {
    }

    public static String encode(String imageSource, String querySource, String appliedQuery, String detailUrl) {
        String img = sanitize(imageSource);
        String qs = StringUtils.defaultIfBlank(sanitize(querySource), "NONE");
        String q = sanitize(appliedQuery);
        String url = sanitize(detailUrl);
        String base = "img=" + img + "|qs=" + qs + "|q=" + q + "|url=";
        // url is the only unbounded field; trim it so the whole reason fits the column.
        int budget = MAX_LEN - base.length();
        if (budget < 0) {
            return StringUtils.left(base, MAX_LEN);
        }
        if (url.length() > budget) {
            url = url.substring(0, budget);
        }
        return base + url;
    }

    /** Parse an encoded reason back into its parts; unknown/missing keys yield empty strings (never null). */
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
                emptyToNull(map.get("img")),
                emptyToNull(map.get("qs")),
                emptyToNull(map.get("q")),
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
    public record Decoded(String imageSource, String querySource, String appliedQuery, String detailUrl) {
    }
}
