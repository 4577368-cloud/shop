package com.tang.plugin.service.publish;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.publish.PublishRequest;
import com.tang.plugin.domain.dto.publish.PublishResultVO;
import com.tang.plugin.domain.dto.publish.ShopifyCreateProductResult;
import com.tang.plugin.domain.entity.publish.ProductPublishRecord;
import com.tang.plugin.enums.publish.ProductPublishStatus;
import com.tang.plugin.repository.ProductPublishRecordRepository;
import com.tang.plugin.service.catalog.TangbuyCatalogService;
import com.tang.plugin.service.publish.component.shopify.ShopifyProductPublishComponent;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests for the publish orchestration (real ledger + H2 + pricing; Shopify create is
 * mocked). Locks: success backfill, PUBLISHED short-circuit (no second Shopify call), PUBLISHING
 * in-progress, write_products precheck, Shopify failure -> FAILED, and SKU passthrough.
 */
@SpringBootTest
@ActiveProfiles("test")
class CatalogPublishServiceTest {

    private static final String SHOP = "publish-svc-shop";
    private static final String DOMAIN = "publish-svc-shop.myshopify.com";

    @Resource
    private CatalogPublishService catalogPublishService;
    @Resource
    private ProductPublishRecordRepository publishRecordRepository;
    @Resource
    private ProductPublishRecordService publishRecordService;
    @Resource
    private TangbuyCatalogService catalogService;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private ShopifyProductPublishComponent shopifyProductPublishComponent;

    private String candidateId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM product_publish_record WHERE shop_name = ?", SHOP);
        jdbcTemplate.update("DELETE FROM shopify_store_auth WHERE shop_name = ?", SHOP);
        candidateId = catalogService.list(1).get(0).getCandidateId();
    }

    private void seedAuth(String scope) {
        jdbcTemplate.update(
                """
                INSERT INTO shopify_store_auth
                (shop_name, shop_domain, access_token, scope, status, authorized_at, updated_at, del_flag)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
                """,
                SHOP, DOMAIN, "token-xyz", scope,
                java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));
    }

    private ShopifyCreateProductResult ok() {
        return new ShopifyCreateProductResult()
                .setProductId("gid://shopify/Product/100")
                .setHandle("test-handle")
                .setVariantId("gid://shopify/ProductVariant/200")
                .setInventoryItemId("gid://shopify/InventoryItem/300");
    }

    private static PublishRequest req(String candidateId) {
        PublishRequest request = new PublishRequest();
        request.setShopName(SHOP);
        request.setCandidateId(candidateId);
        return request;
    }

    @Test
    void publishSuccessMarksPublishedAndBackfills() {
        seedAuth("read_products,write_products");
        when(shopifyProductPublishComponent.createSellableProduct(
                anyString(), anyString(), anyString(), anyString(), any(), any(), anyList()))
                .thenReturn(ok());

        PublishResultVO vo = catalogPublishService.publish(req(candidateId));

        assertEquals("PUBLISHED", vo.getPublishStatus());
        assertEquals("gid://shopify/Product/100", vo.getShopifyProductId());
        ProductPublishRecord row = publishRecordRepository.findByShopAndCandidate(SHOP, candidateId).orElseThrow();
        assertEquals(ProductPublishStatus.PUBLISHED, row.getPublishStatus());
        assertEquals("gid://shopify/ProductVariant/200", row.getShopifyVariantId());
        assertEquals("gid://shopify/InventoryItem/300", row.getShopifyInventoryItemId());
        assertNotNull(row.getPublishedAt());
        assertEquals(1, row.getAttempts());
    }

    @Test
    void repeatWhenPublishedShortCircuitsWithoutSecondShopifyCall() {
        seedAuth("write_products");
        when(shopifyProductPublishComponent.createSellableProduct(
                anyString(), anyString(), anyString(), anyString(), any(), any(), anyList()))
                .thenReturn(ok());

        catalogPublishService.publish(req(candidateId));
        PublishResultVO second = catalogPublishService.publish(req(candidateId));

        assertEquals("PUBLISHED", second.getPublishStatus());
        assertEquals("gid://shopify/Product/100", second.getShopifyProductId());
        verify(shopifyProductPublishComponent, times(1)).createSellableProduct(
                anyString(), anyString(), anyString(), anyString(), any(), any(), anyList());
    }

    @Test
    void inProgressWhenAlreadyPublishing() {
        seedAuth("write_products");
        // Force the ledger into PUBLISHING out of band.
        Long id = publishRecordService.getOrCreate(
                new ProductPublishRecord().setShopName(SHOP).setCandidateId(candidateId)).getId();
        publishRecordService.markPublishing(id);

        PublishResultVO vo = catalogPublishService.publish(req(candidateId));

        assertEquals("PUBLISHING", vo.getPublishStatus());
        verify(shopifyProductPublishComponent, never()).createSellableProduct(
                anyString(), anyString(), anyString(), anyString(), any(), any(), anyList());
    }

    @Test
    void rejectedWhenMissingWriteProductsScope() {
        seedAuth("read_orders,write_orders,read_products");

        assertThrows(CustomException.class, () -> catalogPublishService.publish(req(candidateId)));

        ProductPublishRecord row = publishRecordRepository.findByShopAndCandidate(SHOP, candidateId).orElseThrow();
        assertEquals(ProductPublishStatus.PENDING, row.getPublishStatus());
        assertEquals(0, row.getAttempts());
        verify(shopifyProductPublishComponent, never()).createSellableProduct(
                anyString(), anyString(), anyString(), anyString(), any(), any(), anyList());
    }

    @Test
    void rejectedWhenShopNotAuthorized() {
        // No auth row seeded.
        assertThrows(CustomException.class, () -> catalogPublishService.publish(req(candidateId)));
    }

    @Test
    void rejectedWhenCandidateNotFound() {
        seedAuth("write_products");
        assertThrows(CustomException.class, () -> catalogPublishService.publish(req("no-such-candidate")));
    }

    @Test
    void shopifyFailureMarksFailedAndRethrows() {
        seedAuth("write_products");
        when(shopifyProductPublishComponent.createSellableProduct(
                anyString(), anyString(), anyString(), anyString(), any(), any(), anyList()))
                .thenThrow(new CustomException("Shopify productSet userErrors"));

        assertThrows(CustomException.class, () -> catalogPublishService.publish(req(candidateId)));

        ProductPublishRecord row = publishRecordRepository.findByShopAndCandidate(SHOP, candidateId).orElseThrow();
        assertEquals(ProductPublishStatus.FAILED, row.getPublishStatus());
        assertEquals(1, row.getAttempts());
        assertNotNull(row.getErrorMessage());
    }

    @Test
    void skuPassedAsSanitizedCandidateId() {
        seedAuth("write_products");
        when(shopifyProductPublishComponent.createSellableProduct(
                anyString(), anyString(), anyString(), anyString(), any(), any(), anyList()))
                .thenReturn(ok());

        catalogPublishService.publish(req(candidateId));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<com.tang.plugin.domain.dto.publish.ShopifyVariantCreateInput>> variantsCaptor =
                ArgumentCaptor.forClass(java.util.List.class);
        verify(shopifyProductPublishComponent).createSellableProduct(
                eq(SHOP), eq(DOMAIN), eq("token-xyz"), anyString(), any(), any(), variantsCaptor.capture());
        assertEquals(candidateId.trim().replaceAll("\\s+", ""),
                variantsCaptor.getValue().get(0).getSku());
    }

    @Test
    void descriptionAndImagePassedThroughForEnrichment() {
        seedAuth("write_products");
        when(shopifyProductPublishComponent.createSellableProduct(
                anyString(), anyString(), anyString(), anyString(), any(), any(), anyList()))
                .thenReturn(ok());

        catalogPublishService.publish(req(candidateId));

        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<String>> imageCaptor = ArgumentCaptor.forClass(java.util.List.class);
        verify(shopifyProductPublishComponent).createSellableProduct(
                eq(SHOP), eq(DOMAIN), eq("token-xyz"), anyString(),
                descCaptor.capture(), imageCaptor.capture(), anyList());
        // First catalog entry carries sku_attr + supplier + platform and an https image_url.
        assertNotNull(descCaptor.getValue());
        assertTrue(descCaptor.getValue().contains("<p>"));
        assertNotNull(imageCaptor.getValue());
        assertTrue(!imageCaptor.getValue().isEmpty());
        assertTrue(imageCaptor.getValue().get(0).startsWith("http"));
    }
}
