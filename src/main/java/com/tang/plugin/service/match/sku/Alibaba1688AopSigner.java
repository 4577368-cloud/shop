package com.tang.plugin.service.match.sku;

import com.tang.common.core.exception.CustomException;
import org.apache.commons.lang3.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure, side-effect-free signer for the 1688 open platform (AOP {@code param2} protocol,
 * {@code gw.open.1688.com}); shared by the cross-border image-search, image-upload and offer-detail clients.
 *
 * <p>Signature factor = {@code apiPath} + every request parameter (excluding {@code _aop_signature})
 * concatenated in ascending key order as {@code key + value} with no separators. The signature is
 * {@code HMAC-SHA1(appSecret, factor)} rendered as an upper-case hex string.
 *
 * <p>{@code apiPath} form: {@code param2/1/{namespace}/{apiName}/{appKey}}. No network, no environment
 * access, no Spring — deterministic and fully unit-testable.
 */
public final class Alibaba1688AopSigner {

    private static final String HMAC_SHA1 = "HmacSHA1";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private Alibaba1688AopSigner() {
    }

    /**
     * @param apiPath e.g. {@code param2/1/com.alibaba.fenxiao.crossborder/product.search.queryProductDetail/APPKEY}
     * @param params  all request params except {@code _aop_signature} (system + application level)
     * @return upper-case hex HMAC-SHA1 signature
     */
    public static String sign(String apiPath, Map<String, String> params, String appSecret) {
        if (StringUtils.isBlank(apiPath) || StringUtils.isBlank(appSecret)) {
            throw new CustomException("AOP sign requires apiPath and appSecret");
        }
        TreeMap<String, String> sorted = new TreeMap<>(params == null ? Map.of() : params);
        StringBuilder factor = new StringBuilder(apiPath);
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            factor.append(e.getKey()).append(StringUtils.defaultString(e.getValue()));
        }
        return hmacSha1Hex(appSecret, factor.toString());
    }

    public static String hmacSha1Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA1);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA1));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                hex.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            return hex.toString();
        } catch (Exception e) {
            throw new CustomException("1688 AOP HMAC-SHA1 failed", e);
        }
    }
}
