package com.tang.plugin.service.match.image;

import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured, fixed-format codec for the image-match {@code match_reason} audit column (A3-2b).
 *
 * <p>Format:
 * {@code img=...|qs=...|q=...|pic=...|price=...|title=...|spec=...|url=<detailUrl>}.
 * {@code title}/{@code spec} snapshot the matched candidate for SKU-page回显 when itemGet is unavailable.
 */
public final class ImageMatchReason {

    /** Matches {@code match_reason VARCHAR(1024)}. */
    public static final int MAX_LEN = 1024;
    /** Cap the snapshot image url so the terminal detail url always keeps some budget. */
    private static final int MAX_PIC_LEN = 512;

    private ImageMatchReason() {
    }

    /** Backward-compatible 4-arg encode (no image/price snapshot). */
    public static String encode(String imageSource, String querySource, String appliedQuery, String detailUrl) {
        return encode(imageSource, querySource, appliedQuery, detailUrl, null, null, null, null);
    }

    /** Backward-compatible 6-arg encode (image/price snapshot, no title/spec). */
    public static String encode(String imageSource, String querySource, String appliedQuery,
                                String detailUrl, String imageUrl, String price) {
        return encode(imageSource, querySource, appliedQuery, detailUrl, imageUrl, price, null, null);
    }

    public static String encode(String imageSource, String querySource, String appliedQuery,
                                String detailUrl, String imageUrl, String price,
                                String offerTitle, String skuSpec) {
        String img = sanitize(imageSource);
        String qs = StringUtils.defaultIfBlank(sanitize(querySource), "NONE");
        String q = sanitize(appliedQuery);
        String pic = sanitizeUrl(imageUrl);
        if (pic.length() > MAX_PIC_LEN) {
            pic = pic.substring(0, MAX_PIC_LEN);
        }
        String pr = sanitize(price);
        String title = sanitize(offerTitle);
        String spec = sanitize(skuSpec);
        String base = "img=" + img + "|qs=" + qs + "|q=" + q + "|pic=" + pic + "|price=" + pr
                + "|title=" + title + "|spec=" + spec + "|url=";
        int budget = MAX_LEN - base.length();
        if (budget < 0) {
            return StringUtils.left(base, MAX_LEN);
        }
        String url = sanitizeUrl(detailUrl);
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
                emptyToNull(map.get("url")),
                emptyToNull(map.get("pic")),
                emptyToNull(map.get("price")),
                emptyToNull(map.get("title")),
                emptyToNull(map.get("spec")));
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[|=\\r\\n]", " ").trim();
    }

    private static String sanitizeUrl(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[|\\r\\n]", " ").trim();
    }

    private static String emptyToNull(String v) {
        return StringUtils.isBlank(v) ? null : v;
    }

    public record Decoded(String imageSource, String querySource, String appliedQuery, String detailUrl,
                          String imageUrl, String price, String offerTitle, String skuSpec) {
    }
}
