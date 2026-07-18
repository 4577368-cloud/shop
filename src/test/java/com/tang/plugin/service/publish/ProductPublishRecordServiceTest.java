package com.tang.plugin.service.publish;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.entity.publish.ProductPublishRecord;
import com.tang.plugin.enums.publish.ProductPublishStatus;
import com.tang.plugin.repository.ProductPublishRecordRepository;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the publish ledger base (real repository + H2, test profile). Locks M1-3
 * semantics: idempotent getOrCreate by (shop, candidate); markPublishing only from PENDING/FAILED
 * (attempts++); markPublished idempotent + write-once published_at + no rollback; markFailed only
 * from PUBLISHING; error_message truncated to 1024.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProductPublishRecordServiceTest {

    private static final String SHOP = "publish-test-shop";

    @Resource
    private ProductPublishRecordService service;
    @Resource
    private ProductPublishRecordRepository repository;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM product_publish_record WHERE shop_name = ?", SHOP);
    }

    private ProductPublishRecord snapshot(String candidateId) {
        return new ProductPublishRecord()
                .setShopName(SHOP)
                .setCandidateId(candidateId)
                .setTitle("Test product")
                .setSourcePrice(new BigDecimal("14.4000"))
                .setSourceCurrency("CNY")
                .setSalePrice(new BigDecimal("5.9900"))
                .setTargetCurrency("USD");
    }

    @Test
    void getOrCreateInsertsPendingWithSnapshotAndDefaultShopType() {
        ProductPublishRecord created = service.getOrCreate(snapshot("cand-1"));

        assertNotNull(created.getId());
        assertEquals(ProductPublishStatus.PENDING, created.getPublishStatus());
        assertEquals("SHOPIFY", created.getShopType());
        assertEquals(0, created.getAttempts());
        assertEquals(0, new BigDecimal("14.4000").compareTo(created.getSourcePrice()));
    }

    @Test
    void getOrCreateIsIdempotentBySameCandidate() {
        ProductPublishRecord first = service.getOrCreate(snapshot("cand-dup"));
        ProductPublishRecord second = service.getOrCreate(snapshot("cand-dup"));

        assertEquals(first.getId(), second.getId());
        assertEquals(1, repository.listByShop(SHOP).size());
    }

    @Test
    void getOrCreateRejectsMissingKeys() {
        assertThrows(CustomException.class,
                () -> service.getOrCreate(new ProductPublishRecord().setShopName(SHOP)));
        assertThrows(CustomException.class,
                () -> service.getOrCreate(new ProductPublishRecord().setCandidateId("x")));
    }

    @Test
    void markPublishingFromPendingIncrementsAttempts() {
        Long id = service.getOrCreate(snapshot("cand-pub")).getId();

        assertTrue(service.markPublishing(id));

        ProductPublishRecord row = repository.findById(id).orElseThrow();
        assertEquals(ProductPublishStatus.PUBLISHING, row.getPublishStatus());
        assertEquals(1, row.getAttempts());
    }

    @Test
    void markPublishingRejectedWhileAlreadyPublishing() {
        Long id = service.getOrCreate(snapshot("cand-inflight")).getId();
        assertTrue(service.markPublishing(id));

        assertFalse(service.markPublishing(id));
        assertEquals(1, repository.findById(id).orElseThrow().getAttempts());
    }

    @Test
    void markPublishingAllowedAgainAfterFailedAndIncrementsAttempts() {
        Long id = service.getOrCreate(snapshot("cand-retry")).getId();
        service.markPublishing(id);
        service.markFailed(id, "boom");

        assertTrue(service.markPublishing(id));
        assertEquals(2, repository.findById(id).orElseThrow().getAttempts());
    }

    @Test
    void markPublishedFillsShopifyFieldsAndIsWriteOnce() {
        Long id = service.getOrCreate(snapshot("cand-done")).getId();
        service.markPublishing(id);

        assertTrue(service.markPublished(id,
                "gid://shopify/Product/1", "test-product", "gid://shopify/ProductVariant/2",
                "gid://shopify/InventoryItem/3"));

        ProductPublishRecord row = repository.findById(id).orElseThrow();
        assertEquals(ProductPublishStatus.PUBLISHED, row.getPublishStatus());
        assertEquals("gid://shopify/Product/1", row.getShopifyProductId());
        assertEquals("gid://shopify/ProductVariant/2", row.getShopifyVariantId());
        assertEquals("gid://shopify/InventoryItem/3", row.getShopifyInventoryItemId());
        assertNull(row.getErrorMessage());
        Instant publishedAt = row.getPublishedAt();
        assertNotNull(publishedAt);

        // Replay is a no-op: no rollback, published_at unchanged.
        assertFalse(service.markPublished(id, "gid://shopify/Product/999", "other", "v9", "i9"));
        ProductPublishRecord after = repository.findById(id).orElseThrow();
        assertEquals(ProductPublishStatus.PUBLISHED, after.getPublishStatus());
        assertEquals("gid://shopify/Product/1", after.getShopifyProductId());
        assertEquals(publishedAt, after.getPublishedAt());
    }

    @Test
    void markPublishingRejectedAfterPublished() {
        Long id = service.getOrCreate(snapshot("cand-terminal")).getId();
        service.markPublishing(id);
        service.markPublished(id, "gid://shopify/Product/1", "h", "v", "i");

        assertFalse(service.markPublishing(id));
        assertEquals(ProductPublishStatus.PUBLISHED, repository.findById(id).orElseThrow().getPublishStatus());
    }

    @Test
    void markFailedOnlyFromPublishingAndKeepsAttempts() {
        Long id = service.getOrCreate(snapshot("cand-fail")).getId();
        // Not in PUBLISHING yet -> no-op.
        assertFalse(service.markFailed(id, "early"));

        service.markPublishing(id);
        assertTrue(service.markFailed(id, "real failure"));

        ProductPublishRecord row = repository.findById(id).orElseThrow();
        assertEquals(ProductPublishStatus.FAILED, row.getPublishStatus());
        assertEquals("real failure", row.getErrorMessage());
        assertEquals(1, row.getAttempts());
    }

    @Test
    void markFailedDoesNotOverridePublished() {
        Long id = service.getOrCreate(snapshot("cand-nofail")).getId();
        service.markPublishing(id);
        service.markPublished(id, "gid://shopify/Product/1", "h", "v", "i");

        assertFalse(service.markFailed(id, "late failure"));
        ProductPublishRecord row = repository.findById(id).orElseThrow();
        assertEquals(ProductPublishStatus.PUBLISHED, row.getPublishStatus());
        assertNull(row.getErrorMessage());
    }

    @Test
    void errorMessageTruncatedTo1024() {
        Long id = service.getOrCreate(snapshot("cand-trunc")).getId();
        service.markPublishing(id);
        service.markFailed(id, "x".repeat(2000));

        assertEquals(1024, repository.findById(id).orElseThrow().getErrorMessage().length());
    }
}
