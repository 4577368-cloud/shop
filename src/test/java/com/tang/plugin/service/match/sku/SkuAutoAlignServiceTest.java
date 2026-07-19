package com.tang.plugin.service.match.sku;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.match.SkuAutoAlignResultVO;
import com.tang.plugin.domain.dto.match.SkuProductOverviewVO;
import com.tang.plugin.domain.dto.match.SkuVariantVO;
import com.tang.plugin.domain.dto.match.sku.OfferDetailVO;
import com.tang.plugin.domain.dto.match.sku.OfferSkuAttributeVO;
import com.tang.plugin.domain.dto.match.sku.OfferSkuVO;
import com.tang.plugin.domain.entity.match.ShopProductBinding;
import com.tang.plugin.domain.entity.match.ShopProductMatchCandidate;
import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import com.tang.plugin.enums.match.BindingStatus;
import com.tang.plugin.enums.match.MatchSource;
import com.tang.plugin.repository.ShopProductBindingRepository;
import com.tang.plugin.repository.ShopProductMatchCandidateRepository;
import com.tang.plugin.repository.ThirdPlatformProductRepository;
import com.tang.plugin.repository.ThirdPlatformSkuRepository;
import com.tang.plugin.service.match.SkuBindingOverviewService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Integration tests for S1-b1 auto-align (real repositories + H2; the cross-border detail client is mocked
 * so no network/creds are needed). Locks: per-variant RULE bindings from the offer's SKU matrix, the
 * NOT_BOUND guard, and that the overview echoes the auto-aligned spec.
 */
@SpringBootTest
@ActiveProfiles("test")
class SkuAutoAlignServiceTest {

    private static final String SHOP = "s1b1-shop";
    private static final String ITEM = "gid://shopify/Product/400001";
    private static final String V1 = "gid://shopify/ProductVariant/300001";
    private static final String V2 = "gid://shopify/ProductVariant/300002";
    private static final String OFFER = "913284534444";

    @Resource
    private SkuAutoAlignService service;
    @Resource
    private SkuBindingOverviewService overviewService;
    @Resource
    private ThirdPlatformProductRepository productRepository;
    @Resource
    private ThirdPlatformSkuRepository skuRepository;
    @Resource
    private ShopProductBindingRepository bindingRepository;
    @Resource
    private ShopProductMatchCandidateRepository candidateRepository;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private Crossborder1688ProductClient crossborder1688ProductClient;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM shop_product_binding WHERE shop_name = ?", SHOP);
        jdbcTemplate.update("DELETE FROM shop_product_match_candidate WHERE shop_name = ?", SHOP);
        jdbcTemplate.update("DELETE FROM third_platform_sku WHERE shop_name = ?", SHOP);
        jdbcTemplate.update("DELETE FROM third_platform_product WHERE shop_name = ?", SHOP);
    }

    private void seedProductWithTwoVariants() {
        productRepository.upsert(new ThirdPlatformProduct()
                .setShopName(SHOP).setShopType("SHOPIFY").setThirdPlatformItemId(ITEM).setTitle("Tee"));
        skuRepository.upsert(new ThirdPlatformSku().setShopName(SHOP).setShopType("SHOPIFY")
                .setThirdPlatformItemId(ITEM).setThirdPlatformSkuId(V1).setSku("RED").setOption1("Red").setPosition(1));
        skuRepository.upsert(new ThirdPlatformSku().setShopName(SHOP).setShopType("SHOPIFY")
                .setThirdPlatformItemId(ITEM).setThirdPlatformSkuId(V2).setSku("BLUE").setOption1("Blue").setPosition(2));
    }

    private void seedProductLevelBinding() {
        bindingRepository.upsertActive(new ShopProductBinding()
                .setShopName(SHOP).setShopType("SHOPIFY").setThirdPlatformItemId(ITEM)
                .setThirdPlatformSkuId(V1).setTangbuyProductId(OFFER).setTangbuySkuId(OFFER)
                .setBindSource("FROM_CANDIDATE").setBindStatus(BindingStatus.ACTIVE));
    }

    private void mockOfferRedBlue() {
        OfferDetailVO detail = new OfferDetailVO().setOfferId(OFFER).setSkus(List.of(
                sku("sku-red", "Red"), sku("sku-blue", "Blue")));
        when(crossborder1688ProductClient.queryProductDetail(eq(OFFER), any())).thenReturn(detail);
    }

    private static OfferSkuVO sku(String skuId, String colorTrans) {
        return new OfferSkuVO().setSkuId(skuId).setSkuAttributes(List.of(
                new OfferSkuAttributeVO().setAttributeName("Color").setValue(colorTrans).setValueTrans(colorTrans)));
    }

    @Test
    void autoAlign_bindsEachVariantToMatchingSku() {
        seedProductWithTwoVariants();
        seedProductLevelBinding();
        mockOfferRedBlue();

        SkuAutoAlignResultVO result = service.autoAlign(SHOP, ITEM, null);

        assertEquals(OFFER, result.getOfferId());
        assertEquals(2, result.getTotalVariants());
        assertEquals(2, result.getMatchedCount());

        // Auto-aligned variants land as PENDING (AI-suggested, awaiting confirmation).
        ShopProductBinding red = bindingRepository.findBindableBySkuId(SHOP, V1).orElseThrow();
        assertEquals("sku-red", red.getTangbuySkuId());
        assertEquals("AUTO_ALIGN", red.getBindSource());
        assertEquals(BindingStatus.PENDING, red.getBindStatus());
        assertNotNull(red.getCandidateId());

        ShopProductBinding blue = bindingRepository.findBindableBySkuId(SHOP, V2).orElseThrow();
        assertEquals("sku-blue", blue.getTangbuySkuId());
        assertEquals(BindingStatus.PENDING, blue.getBindStatus());

        ShopProductMatchCandidate candidate = candidateRepository.findById(red.getCandidateId()).orElseThrow();
        assertEquals(MatchSource.RULE, candidate.getMatchSource());
        assertTrue(candidate.getMatchReason().contains("spec=Red"), candidate.getMatchReason());
    }

    @Test
    void overview_echoesAutoAlignedSpec() {
        seedProductWithTwoVariants();
        seedProductLevelBinding();
        mockOfferRedBlue();
        service.autoAlign(SHOP, ITEM, null);

        List<SkuProductOverviewVO> overview = overviewService.overview(SHOP);
        assertEquals(1, overview.size());
        SkuVariantVO red = overview.get(0).getVariants().stream()
                .filter(v -> V1.equals(v.getThirdPlatformSkuId())).findFirst().orElseThrow();
        assertNotNull(red.getBound());
        assertEquals("sku-red", red.getBound().getTangbuySkuId());
        assertEquals("Red", red.getBound().getTangbuySkuSpec());
        assertEquals("RULE", red.getBound().getMatchSource());
    }

    @Test
    void autoAlign_withoutBinding_throwsNotBound() {
        seedProductWithTwoVariants();
        // no product-level binding seeded
        CustomException ex = assertThrows(CustomException.class, () -> service.autoAlign(SHOP, ITEM, null));
        assertTrue(ex.getMessage().startsWith(SkuAutoAlignService.ERR_NOT_BOUND));
    }
}
