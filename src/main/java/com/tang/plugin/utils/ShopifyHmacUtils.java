package com.tang.plugin.utils;

import com.tang.common.core.exception.CustomException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shopify HMAC helpers — OAuth query (hex) and Webhook raw body (base64).
 */
public final class ShopifyHmacUtils {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private ShopifyHmacUtils() {
    }

    public static boolean verifyOAuthQueryHmac(Map<String, String> params, String hmac, String apiSecret) {
        if (StringUtils.isAnyBlank(hmac, apiSecret) || params == null) {
            return false;
        }
        String message = params.entrySet().stream()
                .filter(e -> !"hmac".equalsIgnoreCase(e.getKey()) && !"signature".equalsIgnoreCase(e.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
        String calculated = hmacSha256Hex(apiSecret, message);
        return MessageDigest.isEqual(
                calculated.getBytes(StandardCharsets.UTF_8),
                hmac.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Webhook HMAC must use the raw request body bytes, never a re-serialized JSON.
     */
    public static boolean verifyWebhookRawBodyHmac(byte[] rawBody, String hmacHeader, String apiSecret) {
        if (rawBody == null || StringUtils.isAnyBlank(hmacHeader, apiSecret)) {
            return false;
        }
        String calculated = hmacSha256Base64(apiSecret, rawBody);
        return MessageDigest.isEqual(
                calculated.getBytes(StandardCharsets.UTF_8),
                hmacHeader.getBytes(StandardCharsets.UTF_8));
    }

    public static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Hex.encodeHexString(raw);
        } catch (Exception e) {
            throw new CustomException("HMAC hex failed", e);
        }
    }

    public static String hmacSha256Base64(String secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] raw = mac.doFinal(message);
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new CustomException("HMAC base64 failed", e);
        }
    }
}
