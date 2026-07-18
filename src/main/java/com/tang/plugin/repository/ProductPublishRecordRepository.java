package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.publish.ProductPublishRecord;
import com.tang.plugin.enums.publish.ProductPublishStatus;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * JDBC repository for {@code product_publish_record}. One active row per (shop_name, candidate_id)
 * enforced by uk_ppr_shop_candidate. State transitions are guarded conditional UPDATEs so replays
 * are safe: a transition that no longer applies affects 0 rows.
 */
@Slf4j
@Repository
public class ProductPublishRecordRepository {

    private static final String COLUMNS = """
            id, shop_name, shop_type, candidate_id, tangbuy_product_id, offer_id_1688, sku_id, title,
            source_price, source_currency, sale_price, target_currency, publish_status,
            shopify_product_id, shopify_product_handle, shopify_variant_id, shopify_inventory_item_id,
            attempts, error_message, published_at, del_flag, created_at, updated_at
            """;

    private static final RowMapper<ProductPublishRecord> ROW_MAPPER = (rs, rowNum) -> new ProductPublishRecord()
            .setId(rs.getLong("id"))
            .setShopName(rs.getString("shop_name"))
            .setShopType(rs.getString("shop_type"))
            .setCandidateId(rs.getString("candidate_id"))
            .setTangbuyProductId(rs.getString("tangbuy_product_id"))
            .setOfferId1688(rs.getString("offer_id_1688"))
            .setSkuId(rs.getString("sku_id"))
            .setTitle(rs.getString("title"))
            .setSourcePrice(rs.getBigDecimal("source_price"))
            .setSourceCurrency(rs.getString("source_currency"))
            .setSalePrice(rs.getBigDecimal("sale_price"))
            .setTargetCurrency(rs.getString("target_currency"))
            .setPublishStatus(ProductPublishStatus.valueOf(rs.getString("publish_status")))
            .setShopifyProductId(rs.getString("shopify_product_id"))
            .setShopifyProductHandle(rs.getString("shopify_product_handle"))
            .setShopifyVariantId(rs.getString("shopify_variant_id"))
            .setShopifyInventoryItemId(rs.getString("shopify_inventory_item_id"))
            .setAttempts(rs.getInt("attempts"))
            .setErrorMessage(rs.getString("error_message"))
            .setPublishedAt(toInstant(rs.getTimestamp("published_at")))
            .setDelFlag(rs.getInt("del_flag"))
            .setCreatedAt(toInstant(rs.getTimestamp("created_at")))
            .setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));

    @Resource
    private JdbcTemplate jdbcTemplate;

    public Optional<ProductPublishRecord> findByShopAndCandidate(String shopName, String candidateId) {
        if (StringUtils.isAnyBlank(shopName, candidateId)) {
            return Optional.empty();
        }
        try {
            ProductPublishRecord row = jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM product_publish_record "
                            + "WHERE shop_name = ? AND candidate_id = ? AND del_flag = 0",
                    ROW_MAPPER, shopName, candidateId);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<ProductPublishRecord> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        try {
            ProductPublishRecord row = jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM product_publish_record WHERE id = ?",
                    ROW_MAPPER, id);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<ProductPublishRecord> listByShop(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            return Collections.emptyList();
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM product_publish_record "
                        + "WHERE shop_name = ? AND del_flag = 0 ORDER BY id DESC",
                ROW_MAPPER, shopName);
    }

    /**
     * Insert a PENDING row with the publish snapshot. The unique key (shop_name, candidate_id) is the
     * final safety net against races; callers catch DuplicateKeyException and re-read.
     */
    public Long insertPending(ProductPublishRecord record) {
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO product_publish_record
                    (shop_name, shop_type, candidate_id, tangbuy_product_id, offer_id_1688, sku_id, title,
                     source_price, source_currency, sale_price, target_currency, publish_status,
                     attempts, del_flag, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?)
                    """,
                    new String[]{"id"});
            ps.setString(1, record.getShopName());
            ps.setString(2, record.getShopType());
            ps.setString(3, record.getCandidateId());
            ps.setString(4, record.getTangbuyProductId());
            ps.setString(5, record.getOfferId1688());
            ps.setString(6, record.getSkuId());
            ps.setString(7, record.getTitle());
            ps.setBigDecimal(8, record.getSourcePrice());
            ps.setString(9, record.getSourceCurrency());
            ps.setBigDecimal(10, record.getSalePrice());
            ps.setString(11, record.getTargetCurrency());
            ps.setString(12, ProductPublishStatus.PENDING.name());
            ps.setTimestamp(13, Timestamp.from(now));
            ps.setTimestamp(14, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    /**
     * PENDING or FAILED -> PUBLISHING, incrementing attempts (one increment per real publish start).
     * Guarded: returns 0 when the row is already PUBLISHING/PUBLISHED.
     */
    public int markPublishing(Long id) {
        if (id == null) {
            return 0;
        }
        Timestamp now = Timestamp.from(Instant.now());
        return jdbcTemplate.update(
                "UPDATE product_publish_record "
                        + "SET publish_status = ?, attempts = attempts + 1, updated_at = ? "
                        + "WHERE id = ? AND publish_status IN (?, ?)",
                ProductPublishStatus.PUBLISHING.name(), now, id,
                ProductPublishStatus.PENDING.name(), ProductPublishStatus.FAILED.name());
    }

    /**
     * PUBLISHING -> PUBLISHED, filling Shopify GIDs and writing published_at once. Guarded on
     * PUBLISHING so a replay (already PUBLISHED) is a no-op and never rolls state back or refreshes
     * published_at. error_message is cleared on success.
     */
    public int markPublished(Long id, String shopifyProductId, String shopifyProductHandle,
                             String shopifyVariantId, String shopifyInventoryItemId) {
        if (id == null) {
            return 0;
        }
        Timestamp now = Timestamp.from(Instant.now());
        return jdbcTemplate.update(
                "UPDATE product_publish_record "
                        + "SET publish_status = ?, shopify_product_id = ?, shopify_product_handle = ?, "
                        + "    shopify_variant_id = ?, shopify_inventory_item_id = ?, "
                        + "    error_message = NULL, published_at = ?, updated_at = ? "
                        + "WHERE id = ? AND publish_status = ?",
                ProductPublishStatus.PUBLISHED.name(), shopifyProductId, shopifyProductHandle,
                shopifyVariantId, shopifyInventoryItemId, now, now, id,
                ProductPublishStatus.PUBLISHING.name());
    }

    /**
     * PUBLISHING -> FAILED, recording the last error. Guarded on PUBLISHING so it never overrides a
     * terminal PUBLISHED. attempts is not touched here (it was incremented on markPublishing).
     */
    public int markFailed(Long id, String errorMessage) {
        if (id == null) {
            return 0;
        }
        Timestamp now = Timestamp.from(Instant.now());
        return jdbcTemplate.update(
                "UPDATE product_publish_record "
                        + "SET publish_status = ?, error_message = ?, updated_at = ? "
                        + "WHERE id = ? AND publish_status = ?",
                ProductPublishStatus.FAILED.name(), errorMessage, now, id,
                ProductPublishStatus.PUBLISHING.name());
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
