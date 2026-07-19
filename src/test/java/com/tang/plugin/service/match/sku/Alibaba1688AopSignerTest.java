package com.tang.plugin.service.match.sku;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure tests for the 1688 open-platform AOP signer. The expected signature is an independent reference
 * vector computed with {@code openssl dgst -sha1 -hmac} over the exact factor string, locking the
 * factor construction (apiPath prefix + ascending key order + key||value concatenation) and the
 * upper-case hex HMAC-SHA1 output.
 */
class Alibaba1688AopSignerTest {

    private static final String API_PATH =
            "param2/1/com.alibaba.fenxiao.crossborder/product.search.queryProductDetail/APPKEY";
    private static final String SECRET = "topsecret";
    // openssl: printf '%s' "<apiPath>access_tokenTOKEN123offerDetailParam{...}" | openssl dgst -sha1 -hmac topsecret
    private static final String EXPECTED = "2998287D0EF0F69160B7DBA17A332E8995B11758";

    @Test
    void sign_matchesReferenceVector() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("access_token", "TOKEN123");
        params.put("offerDetailParam", "{\"offerId\":123,\"country\":\"en\"}");
        assertEquals(EXPECTED, Alibaba1688AopSigner.sign(API_PATH, params, SECRET));
    }

    @Test
    void sign_isInsertionOrderIndependent() {
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("offerDetailParam", "{\"offerId\":123,\"country\":\"en\"}");
        reversed.put("access_token", "TOKEN123");
        assertEquals(EXPECTED, Alibaba1688AopSigner.sign(API_PATH, reversed, SECRET));
    }

    @Test
    void hmacSha1Hex_isUpperCase40Chars() {
        String sig = Alibaba1688AopSigner.hmacSha1Hex(SECRET, "anything");
        assertEquals(40, sig.length());
        assertEquals(sig.toUpperCase(), sig);
    }
}
