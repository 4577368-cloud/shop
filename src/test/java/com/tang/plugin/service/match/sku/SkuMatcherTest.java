package com.tang.plugin.service.match.sku;

import com.tang.plugin.domain.dto.match.sku.OfferSkuAttributeVO;
import com.tang.plugin.domain.dto.match.sku.OfferSkuVO;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import com.tang.plugin.service.match.sku.SkuMatcher.VariantAlignment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the S1-b1 variant↔SKU matcher: token-overlap matching against valueTrans/value, the
 * single-SKU fast path, and the below-threshold miss (left unmatched rather than force-bound).
 */
class SkuMatcherTest {

    private static ThirdPlatformSku variant(String gid, String o1, String o2) {
        return new ThirdPlatformSku().setThirdPlatformSkuId(gid).setOption1(o1).setOption2(o2);
    }

    private static OfferSkuVO sku(String skuId, String... valueTrans) {
        List<OfferSkuAttributeVO> attrs = new java.util.ArrayList<>();
        for (String vt : valueTrans) {
            attrs.add(new OfferSkuAttributeVO().setAttributeName("Color").setValue(vt).setValueTrans(vt));
        }
        return new OfferSkuVO().setSkuId(skuId).setSkuAttributes(attrs);
    }

    @Test
    void matchesByTranslatedValue() {
        List<ThirdPlatformSku> variants = List.of(
                variant("v-red", "Red", null),
                variant("v-blue", "Blue", null));
        List<OfferSkuVO> skus = List.of(sku("s-red", "Red"), sku("s-blue", "Blue"));

        List<VariantAlignment> out = SkuMatcher.align(variants, skus);

        assertEquals("s-red", out.get(0).skuId());
        assertTrue(out.get(0).matched());
        assertEquals(1.0d, out.get(0).score());
        assertEquals("Red", out.get(0).specLabel());
        assertEquals("s-blue", out.get(1).skuId());
    }

    @Test
    void twoOptions_partialThenFull() {
        List<ThirdPlatformSku> variants = List.of(variant("v", "Red", "M"));
        List<OfferSkuVO> skus = List.of(
                sku("s-red-l", "Red", "L"),
                sku("s-red-m", "Red", "M"));

        VariantAlignment a = SkuMatcher.align(variants, skus).get(0);
        assertEquals("s-red-m", a.skuId());
        assertEquals(1.0d, a.score());
    }

    @Test
    void singleSku_mapsAllVariants() {
        List<ThirdPlatformSku> variants = List.of(
                variant("v1", "Whatever", null),
                variant("v2", "Nomatch", null));
        List<OfferSkuVO> skus = List.of(sku("only", "标准"));

        List<VariantAlignment> out = SkuMatcher.align(variants, skus);
        assertTrue(out.get(0).matched());
        assertEquals("only", out.get(0).skuId());
        assertTrue(out.get(1).matched());
        assertEquals("only", out.get(1).skuId());
    }

    @Test
    void belowThreshold_leftUnmatched() {
        List<ThirdPlatformSku> variants = List.of(variant("v", "Purple", "XL"));
        List<OfferSkuVO> skus = List.of(sku("s-red-m", "Red", "M"), sku("s-blue-s", "Blue", "S"));

        VariantAlignment a = SkuMatcher.align(variants, skus).get(0);
        assertFalse(a.matched());
        assertNull(a.skuId());
    }

    @Test
    void emptyOfferSkus_unmatched() {
        List<VariantAlignment> out = SkuMatcher.align(List.of(variant("v", "Red", null)), List.of());
        assertFalse(out.get(0).matched());
    }
}
