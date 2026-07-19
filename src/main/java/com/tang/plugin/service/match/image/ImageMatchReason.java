package com.tang.plugin.service.match.image;

import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured, fixed-format codec for the image-match {@code match_reason} audit column (A3-2b).
 * Not free text: a pipe-delimited ordered {@code key=value} string carrying the A3-2a resolution
 * context so the binding stays auditable and machine-parseable.
 *
 * <p>Format:
 * {@code img=<SHOPIFY|ORIGINAL>|qs=<NONE|TITLE|LLM>|q=<appliedQuery>|pic=<imageUrl>|price=<price>|url=<detailUrl>}.
 * Keys are always emitted in this order; empty values are still emitted (empty tail) so positions are
 * stable and adding keys stays backward compatible (old rows simply lack {@code pic}/{@code price}).
 *
 * <p>{@code pic} and {@code price} snapshot the matched candidate's image + price so回显 can render the
 * exact image/price the user saw at match time, without re-querying {@code queryProductDetail} (whose
 * cross-border detail often returns a null white image and an empty SKU matrix).
 *
 * <p>Reserved characters differ per field: value fields ({@code img/qs/q/price}) strip {@code | = \n \r};
 * URL-ish fields ({@code pic/url}) strip only {@code | \n \r} so query-string {@code =} survives intact
 * (previously {@code =} was replaced with a space, corrupting detail links). {@code pic} is capped so
 * the (usually longer) {@code url} keeps budget; {@code url} is the terminal field and absorbs the rest
 * of the {@link #MAX_LEN} ({@code match_reason VARCHAR(1024)}) budget.
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
        return encode(imageSource, querySource, appliedQuery, detailUrl, null, null);
    }

    public static String encode(String imageSource, String querySource, String appliedQuery,
                                String detailUrl, String imageUrl, String price) {
        String img = sanitize(imageSource);
        String qs = StringUtils.defaultIfBlank(sanitize(querySource), "NONE");
        String q = sanitize(appliedQuery);
        String pic = sanitizeUrl(imageUrl);
        if (pic.length() > MAX_PIC_LEN) {
            pic = pic.substring(0, MAX_PIC_LEN);
        }
        String pr = sanitize(price);
        String url = sanitizeUrl(detailUrl);
        String base = "img=" + img + "|qs=" + qs + "|q=" + q + "|pic=" + pic + "|price=" + pr + "|url=";
        // url is the terminal, unbounded field; trim it so the whole reason fits the column.
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
                emptyToNull(map.get("url")),
                emptyToNull(map.get("pic")),
                emptyToNull(map.get("price")));
    }

    /** Value fields: strip pipe/equals/newlines (equals is a structural separator here). */
    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[|=\\r\\n]", " ").trim();
    }

    /** URL-ish fields: keep {@code =} (query params) intact; only pipe/newlines are structural. */
    private static String sanitizeUrl(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[|\\r\\n]", " ").trim();
    }

    private static String emptyToNull(String v) {
        return StringUtils.isBlank(v) ? null : v;
    }

    /** Decoded reason parts (any may be null). {@code imageUrl}/{@code price} snapshot the matched candidate. */
    public record Decoded(String imageSource, String querySource, String appliedQuery, String detailUrl,
                          String imageUrl, String price) {
    }
}
