package com.tang.plugin.service.skualign;

import com.tang.plugin.domain.dto.match.sku.OfferSkuAttributeVO;
import com.tang.plugin.domain.dto.match.sku.OfferSkuVO;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import com.tang.plugin.enums.skualign.VariantReviewState;
import com.tang.plugin.service.match.sku.SkuMatcher;
import com.tang.plugin.service.match.sku.SkuMatcher.VariantAlignment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkuReviewClassifierTest {

    @Test
    void matchedExternalProduct_highConfidenceAutoActive() {
        ThirdPlatformSku variant = shopifySku("v1", "Red", "M");
        List<OfferSkuVO> offerSkus = List.of(offerSku("sku-red-m", "Red", "M"));
        VariantAlignment alignment = SkuMatcher.align(List.of(variant), offerSkus).get(0);

        SkuReviewClassifier.Classification c = SkuReviewClassifier.classify(
                variant, offerSkus, alignment, "offer-1", false, null);

        assertEquals(VariantReviewState.RESOLVED, c.reviewState());
        assertTrue(c.writeV1ActiveBinding());
        assertTrue(!c.writeLegacyPending());
    }

    @Test
    void matchedExternalProduct_mediumConfidenceSuggested() {
        ThirdPlatformSku variant = shopifySku("v5", "Red", "M", "StyleA");
        List<OfferSkuVO> offerSkus = List.of(
                offerSku("sku-red-m-b", "Red", "M", "StyleB"),
                offerSku("sku-red-s-a", "Red", "S", "StyleA"));
        VariantAlignment alignment = SkuMatcher.align(List.of(variant), offerSkus).get(0);

        assertTrue(alignment.matched());
        assertTrue(alignment.score() >= 0.50d && alignment.score() < 0.80d);

        SkuReviewClassifier.Classification c = SkuReviewClassifier.classify(
                variant, offerSkus, alignment, "offer-5", false, null);

        assertEquals(VariantReviewState.SUGGESTED, c.reviewState());
        assertTrue(c.writeLegacyPending());
        assertTrue(!c.writeV1ActiveBinding());
    }

    @Test
    void missingSizeInMatrix_noSource() {
        ThirdPlatformSku variant = shopifySku("v2", "Blue", "XXL");
        List<OfferSkuVO> offerSkus = List.of(
                offerSku("sku-blue-s", "Blue", "S"),
                offerSku("sku-blue-m", "Blue", "M"));
        VariantAlignment alignment = SkuMatcher.align(List.of(variant), offerSkus).get(0);

        assertTrue(SkuMatcher.hasOptionAbsentFromMatrix(variant, offerSkus));

        SkuReviewClassifier.Classification c = SkuReviewClassifier.classify(
                variant, offerSkus, alignment, "offer-2", false, null);

        assertEquals(VariantReviewState.NO_SOURCE, c.reviewState());
    }

    @Test
    void lowOverlap_unmapped() {
        ThirdPlatformSku variant = shopifySku("v3", "A", "B", "C");
        List<OfferSkuVO> offerSkus = List.of(
                offerSku("sku-a", "A", "X", "Y"),
                offerSku("sku-b", "B", "X", "Z"),
                offerSku("sku-c", "C", "X", "W"));
        VariantAlignment alignment = SkuMatcher.align(List.of(variant), offerSkus).get(0);

        assertTrue(!alignment.matched());

        SkuReviewClassifier.Classification c = SkuReviewClassifier.classify(
                variant, offerSkus, alignment, "offer-3", false, null);

        assertEquals(VariantReviewState.UNMAPPED, c.reviewState());
    }

    @Test
    void supplementOfferMatch_externalHighConfidenceAutoActive() {
        ThirdPlatformSku variant = shopifySku("v4", "Blue", "XXL");
        List<OfferSkuVO> supplementSkus = List.of(
                offerSku("sku-blue-xxl", "Blue", "XXL"),
                offerSku("sku-blue-m", "Blue", "M"));
        VariantAlignment alignment = SkuMatcher.align(List.of(variant), supplementSkus).get(0);

        SkuReviewClassifier.Classification c = SkuReviewClassifier.classifySupplementOffer(
                variant, supplementSkus, alignment, "offer-sup", false, null);

        assertEquals(VariantReviewState.RESOLVED, c.reviewState());
        assertTrue(c.writeV1ActiveBinding());
        assertEquals(com.tang.plugin.enums.skualign.VariantBindingState.MULTI_SOURCE, c.effectiveBindingState());
        assertEquals(com.tang.plugin.enums.skualign.SourceRole.SUPPLEMENT, c.effectiveSourceRole());
    }

    private static ThirdPlatformSku shopifySku(String gid, String color, String size) {
        return shopifySku(gid, color, size, null);
    }

    private static ThirdPlatformSku shopifySku(String gid, String o1, String o2, String o3) {
        return new ThirdPlatformSku()
                .setThirdPlatformSkuId(gid)
                .setOption1(o1)
                .setOption2(o2)
                .setOption3(o3);
    }

    private static OfferSkuVO offerSku(String skuId, String... values) {
        List<OfferSkuAttributeVO> attrs = new java.util.ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            attrs.add(attr("Attr" + i, values[i]));
        }
        return new OfferSkuVO().setSkuId(skuId).setSkuAttributes(attrs);
    }

    private static OfferSkuAttributeVO attr(String name, String value) {
        return new OfferSkuAttributeVO()
                .setAttributeName(name)
                .setValue(value)
                .setValueTrans(value);
    }
}
