package com.tang.plugin.service.match.image;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the 1688 Newton signing scheme. No network. Locks each step against the
 * reference integration guide: AK 拆解 → Content-MD5 → canonicalizedResource → x-csk-* 头 → HMAC 签名.
 */
class Newton1688SignerTest {

    @Test
    void extractAkKeys_base64url_splitsSecretThenId() {
        // decoded = secret(32) + id ; base64url-encode as the AK, then extract back.
        String secret = "0123456789abcdef0123456789abcdef"; // exactly 32 chars
        String id = "AK-ID-12345";
        String decoded = secret + id;
        String rawAk = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(decoded.getBytes(StandardCharsets.UTF_8));

        String[] keys = Newton1688Signer.extractAkKeys(rawAk);
        assertEquals(id, keys[0], "AccessKeyID is decoded[32:]");
        assertEquals(secret, keys[1], "AccessKeySecret is decoded[:32]");
    }

    @Test
    void extractAkKeys_blankReturnsNull() {
        assertNull(Newton1688Signer.extractAkKeys(null));
        assertNull(Newton1688Signer.extractAkKeys("  "));
    }

    @Test
    void contentMd5_matchesKnownEmptyVector() {
        // md5("") = d41d8cd98f00b204e9800998ecf8427e ; base64 of those bytes:
        assertEquals("1B2M2Y8AsgTpgAmY7PhCfg==", Newton1688Signer.contentMd5(""));
    }

    @Test
    void canonicalizedResource_pathOnlyWhenNoQuery() {
        assertEquals("/api/find_product/1.0.0",
                Newton1688Signer.canonicalizedResource("/api/find_product/1.0.0"));
        assertEquals("/api/find_product/1.0.0",
                Newton1688Signer.canonicalizedResource(
                        "https://skills-gateway.1688.com/api/find_product/1.0.0"));
    }

    @Test
    void canonicalizedResource_sortsQueryParams() {
        assertEquals("/p?a=1&b=2",
                Newton1688Signer.canonicalizedResource("https://h/p?b=2&a=1"));
    }

    @Test
    void buildAuthHeaders_containsAllCskHeadersAndIsDeterministic() {
        String body = "{\"imageUrl\":\"https://cdn/x.jpg\"}";
        Map<String, String> headers = Newton1688Signer.buildAuthHeaders(
                "POST", "/api/find_product/1.0.0", body,
                "AK-ID", "secret-value", "1700000000", "abcd1234");

        assertEquals("application/json", headers.get("Content-Type"));
        assertEquals("AK-ID", headers.get("x-csk-ak"));
        assertEquals("1700000000", headers.get("x-csk-time"));
        assertEquals("abcd1234", headers.get("x-csk-nonce"));
        assertEquals("1.0.0", headers.get("x-csk-version"));
        assertEquals(Newton1688Signer.contentMd5(body), headers.get("x-csk-content-md5"));
        assertFalse(headers.get("x-csk-sign").isBlank(), "signature must be present");

        // Same inputs → identical signature (deterministic).
        Map<String, String> repeat = Newton1688Signer.buildAuthHeaders(
                "POST", "/api/find_product/1.0.0", body,
                "AK-ID", "secret-value", "1700000000", "abcd1234");
        assertEquals(headers.get("x-csk-sign"), repeat.get("x-csk-sign"));
    }

    @Test
    void buildAuthHeaders_signatureChangesWithBody() {
        Map<String, String> a = Newton1688Signer.buildAuthHeaders(
                "POST", "/api/find_product/1.0.0", "{\"imageUrl\":\"a\"}",
                "AK-ID", "secret", "1700000000", "abcd1234");
        Map<String, String> b = Newton1688Signer.buildAuthHeaders(
                "POST", "/api/find_product/1.0.0", "{\"imageUrl\":\"b\"}",
                "AK-ID", "secret", "1700000000", "abcd1234");
        assertNotEquals(a.get("x-csk-sign"), b.get("x-csk-sign"));
    }

    @Test
    void hmacSha256Base64_isStableBase64() {
        String sig = Newton1688Signer.hmacSha256Base64("secret", "hello");
        assertTrue(sig.matches("^[A-Za-z0-9+/]+={0,2}$"), "must be valid base64");
        assertEquals(sig, Newton1688Signer.hmacSha256Base64("secret", "hello"));
    }
}
