package com.tang.plugin.service.match.image;

import com.tang.common.core.exception.CustomException;
import org.apache.commons.lang3.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure, side-effect-free signing helpers for the 1688 Newton Hub (skills-gateway.1688.com).
 * Mirrors the reference Python implementation of the {@code find_product} AK HMAC-SHA256 scheme:
 * AK 拆解 → Content-MD5 → canonicalizedResource → x-csk-* 头 → HMAC-SHA256 签名。
 *
 * <p>No network, no environment access, no Spring. Kept deterministic so it is fully unit-testable
 * (callers inject the timestamp and nonce).
 */
public final class Newton1688Signer {

    public static final String SKILL_VERSION = "1.0.0";
    private static final String CONTENT_TYPE = "application/json";
    private static final String HMAC_SHA256 = "HmacSHA256";

    private Newton1688Signer() {
    }

    /**
     * Decode an AK (AccessKey) into its {@code [AccessKeyID, AccessKeySecret]} parts.
     * base64url-decoded: first 32 chars are the Secret, the rest is the ID.
     * Fallback: if it cannot be base64-decoded, split the raw string at index 32.
     *
     * @return a 2-element array {@code [id, secret]}, or {@code null} when the AK is blank/invalid.
     */
    public static String[] extractAkKeys(String rawAk) {
        if (StringUtils.isBlank(rawAk)) {
            return null;
        }
        String ak = rawAk.trim();
        try {
            int padLen = (4 - (ak.length() % 4)) % 4;
            String padded = ak + "=".repeat(padLen);
            byte[] decodedBytes = Base64.getUrlDecoder().decode(padded);
            String decoded = new String(decodedBytes, StandardCharsets.UTF_8);
            if (decoded.length() > 32) {
                String secret = decoded.substring(0, 32);
                String id = decoded.substring(32);
                if (StringUtils.isNotEmpty(id)) {
                    return new String[]{id, secret};
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the raw-split fallback
        }
        if (ak.length() > 32) {
            return new String[]{ak.substring(32), ak.substring(0, 32)};
        }
        return null;
    }

    /** {@code base64(md5(body_utf8))}. */
    public static String contentMd5(String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(StringUtils.defaultString(body).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new CustomException("1688 Content-MD5 failed", e);
        }
    }

    /**
     * The URL path plus its sorted query string. For the fixed {@code find_product} endpoint there is
     * no query, so this returns just the path; the general (sorted) form is kept for correctness.
     */
    public static String canonicalizedResource(String uri) {
        URI parsed = URI.create(uri);
        String path = parsed.getRawPath();
        if (StringUtils.isEmpty(path)) {
            path = "/";
        }
        String query = parsed.getRawQuery();
        if (StringUtils.isEmpty(query)) {
            return path;
        }
        TreeMap<String, List<String>> params = new TreeMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            params.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : params.entrySet()) {
            e.getValue().stream().sorted().forEach(v ->
                    parts.add(encode(e.getKey()) + "=" + encode(v)));
        }
        return path + "?" + String.join("&", parts);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Build the full request headers (Content-Type + x-csk-sign + 5 x-csk-* headers).
     * {@code timeSec} and {@code nonce} are injected so the result is deterministic under test.
     */
    public static Map<String, String> buildAuthHeaders(String method, String uri, String body,
                                                       String accessKeyId, String accessKeySecret,
                                                       String timeSec, String nonce) {
        String md5 = contentMd5(body);
        // TreeMap → keys ascending: x-csk-ak, x-csk-content-md5, x-csk-nonce, x-csk-time, x-csk-version
        TreeMap<String, String> csk = new TreeMap<>();
        csk.put("x-csk-ak", accessKeyId);
        csk.put("x-csk-time", timeSec);
        csk.put("x-csk-nonce", nonce);
        csk.put("x-csk-content-md5", md5);
        csk.put("x-csk-version", SKILL_VERSION);

        StringBuilder canonHeaders = new StringBuilder();
        for (Map.Entry<String, String> e : csk.entrySet()) {
            canonHeaders.append(e.getKey().toLowerCase())
                    .append(':')
                    .append(StringUtils.trimToEmpty(e.getValue()))
                    .append('\n');
        }
        String stringToSign = String.join("\n",
                method.toUpperCase(), md5, CONTENT_TYPE, timeSec)
                + "\n" + canonHeaders + canonicalizedResource(uri);
        String sign = hmacSha256Base64(accessKeySecret, stringToSign);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", CONTENT_TYPE);
        headers.put("x-csk-sign", sign);
        headers.putAll(csk);
        return headers;
    }

    public static String hmacSha256Base64(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new CustomException("1688 HMAC-SHA256 failed", e);
        }
    }
}
