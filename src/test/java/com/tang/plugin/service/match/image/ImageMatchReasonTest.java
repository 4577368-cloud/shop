package com.tang.plugin.service.match.image;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the structured {@code match_reason} codec (A3-2b): fixed key order, reserved-char
 * sanitizing, url length capping, and lossless-enough round trip. No Spring, no DB.
 */
class ImageMatchReasonTest {

    @Test
    void encodeThenDecode_roundTrips() {
        String reason = ImageMatchReason.encode("SHOPIFY", "LLM", "礼盒", "https://detail.1688.com/offer/1.html");
        ImageMatchReason.Decoded d = ImageMatchReason.decode(reason);
        assertEquals("SHOPIFY", d.imageSource());
        assertEquals("LLM", d.querySource());
        assertEquals("礼盒", d.appliedQuery());
        assertEquals("https://detail.1688.com/offer/1.html", d.detailUrl());
    }

    @Test
    void fixedKeyOrderAndDefaults() {
        String reason = ImageMatchReason.encode("ORIGINAL", null, null, null);
        assertTrue(reason.startsWith("img=ORIGINAL|qs=NONE|q=|pic=|price=|title=|spec=|url="), reason);
        ImageMatchReason.Decoded d = ImageMatchReason.decode(reason);
        assertEquals("ORIGINAL", d.imageSource());
        assertEquals("NONE", d.querySource());
        assertNull(d.appliedQuery());
        assertNull(d.detailUrl());
        assertNull(d.imageUrl());
        assertNull(d.price());
    }

    @Test
    void snapshotImageAndPriceRoundTrip() {
        String reason = ImageMatchReason.encode("SHOPIFY", "TITLE", "礼盒",
                "https://detail.1688.com/offer/1.html",
                "https://cbu01.alicdn.com/img/ibank/x.jpg", "12.00");
        ImageMatchReason.Decoded d = ImageMatchReason.decode(reason);
        assertEquals("https://cbu01.alicdn.com/img/ibank/x.jpg", d.imageUrl());
        assertEquals("12.00", d.price());
        assertEquals("https://detail.1688.com/offer/1.html", d.detailUrl());
    }

    @Test
    void urlQueryParamsPreserveEquals() {
        // Regression: previously '=' in the url was replaced with a space, corrupting detail links.
        String url = "https://detail.1688.com/offer/557466677092.html?fromkvrefer=HVMTG&kjSource=pc";
        String reason = ImageMatchReason.encode("SHOPIFY", "TITLE", "标题", url,
                "https://img?a=b&c=d", null);
        ImageMatchReason.Decoded d = ImageMatchReason.decode(reason);
        assertEquals(url, d.detailUrl());
        assertEquals("https://img?a=b&c=d", d.imageUrl());
    }

    @Test
    void reservedCharsStripped() {
        String reason = ImageMatchReason.encode("SHOPIFY", "TITLE", "a|b=c\nd", "u\rl");
        // no pipe/equals/newline should leak into values beyond the structural separators
        ImageMatchReason.Decoded d = ImageMatchReason.decode(reason);
        assertEquals("TITLE", d.querySource());
        assertEquals("a b c d", d.appliedQuery());
        assertEquals("u l", d.detailUrl());
    }

    @Test
    void urlCappedToColumnWidth() {
        String longUrl = "https://x/" + "a".repeat(2000);
        String reason = ImageMatchReason.encode("SHOPIFY", "TITLE", "标题", longUrl);
        assertTrue(reason.length() <= ImageMatchReason.MAX_LEN, "len=" + reason.length());
        // structural prefix and the other fields are preserved
        ImageMatchReason.Decoded d = ImageMatchReason.decode(reason);
        assertEquals("标题", d.appliedQuery());
        assertTrue(d.detailUrl().startsWith("https://x/aaa"));
    }

    @Test
    void titleAndSpecRoundTrip() {
        String reason = ImageMatchReason.encode("SHOPIFY", "TITLE", "q",
                "https://detail.1688.com/offer/1.html",
                "https://img/x.jpg", "9.90", "针织上衣", "杏色 / S");
        ImageMatchReason.Decoded d = ImageMatchReason.decode(reason);
        assertEquals("针织上衣", d.offerTitle());
        assertEquals("杏色 / S", d.skuSpec());
    }

    @Test
    void decodeBlankYieldsAllNull() {
        ImageMatchReason.Decoded d = ImageMatchReason.decode(null);
        assertNull(d.imageSource());
        assertNull(d.querySource());
        assertNull(d.appliedQuery());
        assertNull(d.detailUrl());
    }
}
