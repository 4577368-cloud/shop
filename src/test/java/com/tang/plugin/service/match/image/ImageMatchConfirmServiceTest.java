package com.tang.plugin.service.match.image;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.match.ConfirmImageMatchDTO;
import com.tang.plugin.domain.dto.match.ImageBindingView;
import com.tang.plugin.domain.dto.match.sku.OfferDetailVO;
import com.tang.plugin.domain.dto.match.sku.OfferSkuVO;
import com.tang.plugin.domain.entity.match.ShopProductBinding;
import com.tang.plugin.domain.entity.match.ShopProductMatchCandidate;
import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import com.tang.plugin.enums.match.BindingStatus;
import com.tang.plugin.enums.match.MatchSource;
import com.tang.plugin.enums.match.MatchStatus;
import com.tang.plugin.repository.ShopProductBindingRepository;
import com.tang.plugin.repository.ShopProductMatchCandidateRepository;
import com.tang.plugin.repository.ThirdPlatformProductRepository;
import com.tang.plugin.repository.ThirdPlatformSkuRepository;
import com.tang.plugin.service.match.sku.Crossborder1688ProductClient;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for A3-2b confirm (real repositories + H2, test profile). Locks route-B semantics:
 * default variant resolved from the SKU mirror, IMAGE candidate (CONFIRMED, score + structured reason),
 * ACTIVE binding FROM_CANDIDATE, rebind on a different offer, and the two guard errors.
 */
@SpringBootTest
@ActiveProfiles("test")
class ImageMatchConfirmServiceTest {

    private static final String SHOP = "a32b-test-shop";
    private static final String ITEM = "gid://shopify/Product/900001";
    private static final String VARIANT = "gid://shopify/ProductVariant/700001";

    @Resource
    private ImageMatchConfirmService service;
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
        mockOfferMatrix("111", "111");
        mockOfferMatrix("222", "222");
    }

    private void mockOfferMatrix(String offerId, String defaultSkuId) {
        OfferDetailVO detail = new OfferDetailVO().setOfferId(offerId).setSkus(List.of(
                new OfferSkuVO().setSkuId(defaultSkuId)));
        when(crossborder1688ProductClient.queryProductDetail(eq(offerId), any())).thenReturn(detail);
    }

    private void seedProductWithVariant() {
        productRepository.upsert(new ThirdPlatformProduct()
                .setShopName(SHOP).setShopType("SHOPIFY").setThirdPlatformItemId(ITEM).setTitle("Test"));
        skuRepository.upsert(new ThirdPlatformSku()
                .setShopName(SHOP).setShopType("SHOPIFY").setThirdPlatformItemId(ITEM)
                .setThirdPlatformSkuId(VARIANT).setSku("SKU-1").setPosition(1));
    }

    private ConfirmImageMatchDTO dto(String offerId, Double score) {
        return new ConfirmImageMatchDTO()
                .setShopName(SHOP).setThirdPlatformItemId(ITEM)
                .setOfferProductId(offerId)
                .setDetailUrl("https://detail.1688.com/offer/" + offerId + ".html")
                .setSimilarityScore(score)
                .setImageSource("SHOPIFY").setQuerySource("LLM").setAppliedQuery("礼盒");
    }

    @Test
    void confirm_createsImageCandidateAndActiveBinding() {
        seedProductWithVariant();

        ImageBindingView view = service.confirm(dto("111", 0.9982515));

        assertTrue(view.isBound());
        assertEquals(VARIANT, view.getThirdPlatformSkuId());
        assertEquals("111", view.getTangbuyProductId());
        assertEquals("111", view.getTangbuySkuId()); // offerSkuId blank → first matrix sku
        assertEquals("LLM", view.getQuerySource());
        assertEquals("礼盒", view.getAppliedQuery());

        Optional<ShopProductBinding> binding = bindingRepository.findActiveBySkuId(SHOP, VARIANT);
        assertTrue(binding.isPresent());
        assertEquals("FROM_CANDIDATE", binding.get().getBindSource());
        assertEquals(BindingStatus.ACTIVE, binding.get().getBindStatus());
        assertNotNull(binding.get().getCandidateId());

        ShopProductMatchCandidate candidate = candidateRepository.findById(binding.get().getCandidateId()).orElseThrow();
        assertEquals(MatchSource.IMAGE, candidate.getMatchSource());
        assertEquals(MatchStatus.CONFIRMED, candidate.getStatus());
        assertEquals(0, new BigDecimal("0.9983").compareTo(candidate.getMatchScore())); // 4dp HALF_UP
        assertTrue(candidate.getMatchReason().contains("qs=LLM"));
    }

    @Test
    void confirmDifferentOffer_rebindsAndKeepsSingleActive() {
        seedProductWithVariant();
        service.confirm(dto("111", 0.9));
        service.confirm(dto("222", 0.8));

        List<ImageBindingView> active = service.listActiveBindings(SHOP);
        assertEquals(1, active.size());
        assertEquals("222", active.get(0).getTangbuyProductId());

        ShopProductBinding binding = bindingRepository.findActiveBySkuId(SHOP, VARIANT).orElseThrow();
        assertEquals("222", binding.getTangbuySkuId());
    }

    @Test
    void autoConfirm_landsPending_thenAckPromotesToActive() {
        seedProductWithVariant();

        ImageBindingView view = service.confirm(dto("111", 0.9).setAuto(true));
        assertEquals(BindingStatus.PENDING.name(), view.getBindStatus());

        // PENDING is not an ACTIVE binding yet, but is surfaced for review (listActiveBindings).
        assertTrue(bindingRepository.findActiveBySkuId(SHOP, VARIANT).isEmpty());
        ShopProductBinding pending = bindingRepository.findBindableBySkuId(SHOP, VARIANT).orElseThrow();
        assertEquals(BindingStatus.PENDING, pending.getBindStatus());
        assertEquals(1, service.listActiveBindings(SHOP).size());

        service.acknowledge(SHOP, ITEM);
        ShopProductBinding confirmed = bindingRepository.findActiveBySkuId(SHOP, VARIANT).orElseThrow();
        assertEquals(BindingStatus.ACTIVE, confirmed.getBindStatus());
    }

    @Test
    void unbind_softDeletesBinding() {
        seedProductWithVariant();
        service.confirm(dto("111", 0.9)); // ACTIVE

        service.unbind(SHOP, ITEM);
        assertTrue(bindingRepository.findBindableBySkuId(SHOP, VARIANT).isEmpty());
        assertTrue(service.listActiveBindings(SHOP).isEmpty());
    }

    @Test
    void nullSimilarity_storesZeroScore() {
        seedProductWithVariant();
        service.confirm(dto("111", null));
        ShopProductMatchCandidate candidate =
                candidateRepository.listByShopAndStatus(SHOP, MatchStatus.CONFIRMED).get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(candidate.getMatchScore()));
    }

    @Test
    void missingVariant_throwsNoVariant() {
        productRepository.upsert(new ThirdPlatformProduct()
                .setShopName(SHOP).setShopType("SHOPIFY").setThirdPlatformItemId(ITEM).setTitle("Test"));
        // no SKU seeded
        CustomException ex = assertThrows(CustomException.class, () -> service.confirm(dto("111", 0.9)));
        assertTrue(ex.getMessage().startsWith(ImageMatchConfirmService.ERR_NO_VARIANT));
    }

    @Test
    void unknownProduct_throwsProductNotFound() {
        CustomException ex = assertThrows(CustomException.class, () -> service.confirm(dto("111", 0.9)));
        assertTrue(ex.getMessage().startsWith(ImageMatchConfirmService.ERR_PRODUCT_NOT_FOUND));
    }
}
