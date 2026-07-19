package com.tang.plugin.service.match;

import com.tang.plugin.domain.dto.match.ConfirmImageMatchDTO;
import com.tang.plugin.domain.dto.match.SkuProductOverviewVO;
import com.tang.plugin.domain.dto.match.SkuVariantVO;
import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import com.tang.plugin.repository.ThirdPlatformProductRepository;
import com.tang.plugin.repository.ThirdPlatformSkuRepository;
import com.tang.plugin.service.match.image.ImageMatchConfirmService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for S1-a SKU overview (real repositories + H2). Locks: only bound products are
 * returned, aggregated per product and expanded per variant (position asc), with correct per-variant
 * bound/unbound state, decoded source context, and the optionLabel fallback.
 */
@SpringBootTest
@ActiveProfiles("test")
class SkuBindingOverviewServiceTest {

    private static final String SHOP = "s1a-test-shop";
    private static final String ITEM = "gid://shopify/Product/800001";
    private static final String V1 = "gid://shopify/ProductVariant/600001";
    private static final String V2 = "gid://shopify/ProductVariant/600002";

    @Resource
    private SkuBindingOverviewService service;
    @Resource
    private ImageMatchConfirmService confirmService;
    @Resource
    private ThirdPlatformProductRepository productRepository;
    @Resource
    private ThirdPlatformSkuRepository skuRepository;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM shop_product_binding WHERE shop_name = ?", SHOP);
        jdbcTemplate.update("DELETE FROM shop_product_match_candidate WHERE shop_name = ?", SHOP);
        jdbcTemplate.update("DELETE FROM third_platform_sku WHERE shop_name = ?", SHOP);
        jdbcTemplate.update("DELETE FROM third_platform_product WHERE shop_name = ?", SHOP);
    }

    private void seedTwoVariantProduct() {
        productRepository.upsert(new ThirdPlatformProduct()
                .setShopName(SHOP).setShopType("SHOPIFY").setThirdPlatformItemId(ITEM)
                .setTitle("Test Product").setPrimaryImageUrl("https://img/p.jpg"));
        skuRepository.upsert(new ThirdPlatformSku()
                .setShopName(SHOP).setShopType("SHOPIFY").setThirdPlatformItemId(ITEM)
                .setThirdPlatformSkuId(V1).setSku("SKU-RED").setOption1("红色").setPosition(1));
        skuRepository.upsert(new ThirdPlatformSku()
                .setShopName(SHOP).setShopType("SHOPIFY").setThirdPlatformItemId(ITEM)
                .setThirdPlatformSkuId(V2).setSku("SKU-BLUE").setOption1("蓝色").setPosition(2));
    }

    @Test
    void overview_returnsOnlyBoundProductsExpandedPerVariant() {
        seedTwoVariantProduct();
        // bind the default (first) variant to a 1688 offer
        confirmService.confirm(new ConfirmImageMatchDTO()
                .setShopName(SHOP).setThirdPlatformItemId(ITEM).setOfferProductId("777")
                .setDetailUrl("https://detail.1688.com/offer/777.html")
                .setSimilarityScore(0.95).setImageSource("SHOPIFY")
                .setQuerySource("LLM").setAppliedQuery("红色礼盒"));

        List<SkuProductOverviewVO> overview = service.overview(SHOP);
        assertEquals(1, overview.size());
        SkuProductOverviewVO p = overview.get(0);
        assertEquals(ITEM, p.getThirdPlatformItemId());
        assertEquals("Test Product", p.getTitle());
        assertEquals(2, p.getVariants().size());

        SkuVariantVO first = p.getVariants().get(0); // position 1
        assertEquals(V1, first.getThirdPlatformSkuId());
        assertEquals("红色", first.getOptionLabel());
        assertNotNull(first.getBound());
        assertEquals("777", first.getBound().getTangbuyProductId());
        assertEquals("LLM", first.getBound().getQuerySource());
        assertEquals("红色礼盒", first.getBound().getAppliedQuery());
        assertNotNull(first.getBound().getBindingId());
        assertNotNull(first.getBound().getCandidateId());

        SkuVariantVO second = p.getVariants().get(1); // position 2, unbound
        assertEquals(V2, second.getThirdPlatformSkuId());
        assertNull(second.getBound());
    }

    @Test
    void overview_emptyWhenNoBindings() {
        seedTwoVariantProduct(); // product + variants exist but no binding
        assertTrue(service.overview(SHOP).isEmpty());
    }

    @Test
    void optionLabel_fallsBackToSkuThenGeneric() {
        productRepository.upsert(new ThirdPlatformProduct()
                .setShopName(SHOP).setShopType("SHOPIFY").setThirdPlatformItemId(ITEM).setTitle("P"));
        // no options, but a sku present
        skuRepository.upsert(new ThirdPlatformSku()
                .setShopName(SHOP).setShopType("SHOPIFY").setThirdPlatformItemId(ITEM)
                .setThirdPlatformSkuId(V1).setSku("ONLY-SKU").setPosition(1));
        confirmService.confirm(new ConfirmImageMatchDTO()
                .setShopName(SHOP).setThirdPlatformItemId(ITEM).setOfferProductId("777"));

        SkuVariantVO v = service.overview(SHOP).get(0).getVariants().get(0);
        assertEquals("ONLY-SKU", v.getOptionLabel());
    }
}
