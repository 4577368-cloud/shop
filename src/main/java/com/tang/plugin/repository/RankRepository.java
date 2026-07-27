package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.ranking.RankProduct;
import com.tang.plugin.domain.entity.ranking.RankSnapshot;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC repository for the TikTok ranking board ({@code rank_snapshot} +
 * {@code rank_product}). Snapshot keyed by (shop_name, date_range); re-importing
 * the same date_range replaces its products (refresh semantics).
 */
@Slf4j
@Repository
public class RankRepository {

    private static final int BATCH_SIZE = 500;

    private static final RowMapper<RankSnapshot> SNAPSHOT_ROW_MAPPER = (rs, rowNum) -> new RankSnapshot()
            .setId(rs.getLong("id"))
            .setShopName(rs.getString("shop_name"))
            .setCountry(rs.getString("country"))
            .setDateRange(rs.getString("date_range"))
            .setStartDate(rs.getObject("start_date", LocalDate.class))
            .setEndDate(rs.getObject("end_date", LocalDate.class))
            .setProductCount(rs.getInt("product_count"))
            .setCreatedAt(toInstant(rs.getTimestamp("created_at")));

    private static final RowMapper<RankProduct> PRODUCT_ROW_MAPPER = (rs, rowNum) -> new RankProduct()
            .setId(rs.getLong("id"))
            .setSnapshotId(rs.getLong("snapshot_id"))
            .setShopName(rs.getString("shop_name"))
            .setRankNo((Integer) rs.getObject("rank_no"))
            .setProductTitle(rs.getString("product_title"))
            .setImageUrl(rs.getString("image_url"))
            .setCategoryL1(rs.getString("category_l1"))
            .setCategoryL2(rs.getString("category_l2"))
            .setCategoryL3(rs.getString("category_l3"))
            .setCategoryPath(rs.getString("category_path"))
            .setPriceUsd(rs.getBigDecimal("price_usd"))
            .setAvgPriceUsd(rs.getBigDecimal("avg_price_usd"))
            .setListedAt(rs.getObject("listed_at", LocalDate.class))
            .setRating((Double) rs.getObject("rating"))
            .setSalesVolume((Long) rs.getObject("sales_volume"))
            .setCommissionRate((Double) rs.getObject("commission_rate"))
            .setGmvUsd(rs.getBigDecimal("gmv_usd"))
            .setGmvGrowthRate((Double) rs.getObject("gmv_growth_rate"))
            .setLiveGmvUsd(rs.getBigDecimal("live_gmv_usd"))
            .setVideoGmvUsd(rs.getBigDecimal("video_gmv_usd"))
            .setCardGmvUsd(rs.getBigDecimal("card_gmv_usd"))
            .setCreatorCount((Integer) rs.getObject("creator_count"))
            .setCreatorOrderRate((Double) rs.getObject("creator_order_rate"))
            .setTiktokUrl(rs.getString("tiktok_url"))
            .setCountry(rs.getString("country"));

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * Upsert a snapshot by (shop_name, date_range, country). Returns the snapshot id.
     * Updates product_count / date bounds when the snapshot already exists.
     */
    public Long upsertSnapshot(String shopName, String country, String dateRange, LocalDate startDate,
                              LocalDate endDate, int productCount) {
        Long existing = findActiveSnapshotId(shopName, country, dateRange);
        Timestamp now = Timestamp.from(Instant.now());
        if (existing != null) {
            jdbcTemplate.update(
                    """
                    UPDATE rank_snapshot
                    SET start_date = ?, end_date = ?, product_count = ?, updated_at = ?, del_flag = 0
                    WHERE id = ?
                    """,
                    startDate, endDate, productCount, now, existing);
            return existing;
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    """
                    INSERT INTO rank_snapshot
                    (shop_name, country, date_range, start_date, end_date, product_count, created_at, updated_at, del_flag)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """,
                    new String[]{"id"});
            ps.setString(1, shopName);
            ps.setString(2, country == null ? "" : country);
            ps.setString(3, dateRange);
            ps.setObject(4, startDate);
            ps.setObject(5, endDate);
            ps.setInt(6, productCount);
            ps.setTimestamp(7, now);
            ps.setTimestamp(8, now);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    private Long findActiveSnapshotId(String shopName, String country, String dateRange) {
        if (shopName == null || dateRange == null) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM rank_snapshot WHERE shop_name = ? AND country = ? AND date_range = ? AND del_flag = 0",
                    Long.class, shopName, country == null ? "" : country, dateRange);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * Replace all products for a snapshot (refresh semantics): delete existing
     * active rows, then batch-insert the new ones.
     */
    public void replaceProducts(Long snapshotId, String shopName, String country, List<RankProduct> products) {
        if (snapshotId == null) {
            return;
        }
        jdbcTemplate.update(
                "DELETE FROM rank_product WHERE snapshot_id = ? AND shop_name = ? AND del_flag = 0",
                snapshotId, shopName);
        if (products.isEmpty()) {
            return;
        }
        for (int start = 0; start < products.size(); start += BATCH_SIZE) {
            int end = Math.min(products.size(), start + BATCH_SIZE);
            List<RankProduct> chunk = products.subList(start, end);
            jdbcTemplate.batchUpdate(
                    """
                    INSERT INTO rank_product
                    (snapshot_id, shop_name, rank_no, product_title, image_url, category_l1, category_l2,
                     category_l3, category_path, price_usd, avg_price_usd, listed_at, rating, sales_volume,
                     commission_rate, gmv_usd, gmv_growth_rate, live_gmv_usd, video_gmv_usd, card_gmv_usd,
                     creator_count, creator_order_rate, tiktok_url, country, del_flag)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """,
                    new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
                            RankProduct p = chunk.get(i);
                            ps.setLong(1, snapshotId);
                            ps.setString(2, shopName);
                            ps.setObject(3, p.getRankNo());
                            ps.setString(4, p.getProductTitle());
                            ps.setString(5, p.getImageUrl());
                            ps.setString(6, p.getCategoryL1());
                            ps.setString(7, p.getCategoryL2());
                            ps.setString(8, p.getCategoryL3());
                            ps.setString(9, p.getCategoryPath());
                            ps.setBigDecimal(10, p.getPriceUsd());
                            ps.setBigDecimal(11, p.getAvgPriceUsd());
                            ps.setObject(12, p.getListedAt());
                            ps.setObject(13, p.getRating());
                            ps.setObject(14, p.getSalesVolume());
                            ps.setObject(15, p.getCommissionRate());
                            ps.setBigDecimal(16, p.getGmvUsd());
                            ps.setObject(17, p.getGmvGrowthRate());
                            ps.setBigDecimal(18, p.getLiveGmvUsd());
                            ps.setBigDecimal(19, p.getVideoGmvUsd());
                            ps.setBigDecimal(20, p.getCardGmvUsd());
                            ps.setObject(21, p.getCreatorCount());
                            ps.setObject(22, p.getCreatorOrderRate());
                            ps.setString(23, p.getTiktokUrl());
                            ps.setString(24, country == null ? "" : country);
                        }

                        @Override
                        public int getBatchSize() {
                            return chunk.size();
                        }
                    });
        }
    }

    /**
     * List active snapshots for a shop, most recent window first.
     */
    public List<RankSnapshot> listSnapshots(String shopName) {
        if (shopName == null) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT id, shop_name, country, date_range, start_date, end_date, product_count, created_at
                FROM rank_snapshot
                WHERE shop_name = ? AND del_flag = 0
                ORDER BY start_date DESC NULLS LAST, id DESC
                """,
                SNAPSHOT_ROW_MAPPER, shopName);
    }

    /**
     * List active products for a snapshot, optionally filtered by L1 category.
     * Ordered by GMV desc (the board ranking), then sales volume desc.
     * Hard-capped by {@code limit} to avoid loading oversized boards into memory.
     */
    public List<RankProduct> listProducts(String shopName, Long snapshotId, String categoryL1, int limit) {
        if (shopName == null || snapshotId == null) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        params.add(snapshotId);
        params.add(shopName);
        StringBuilder sql = new StringBuilder(
                """
                SELECT id, snapshot_id, shop_name, rank_no, product_title, image_url, category_l1, category_l2,
                       category_l3, category_path, price_usd, avg_price_usd, listed_at, rating, sales_volume,
                       commission_rate, gmv_usd, gmv_growth_rate, live_gmv_usd, video_gmv_usd, card_gmv_usd,
                       creator_count, creator_order_rate, tiktok_url, country
                FROM rank_product
                WHERE snapshot_id = ? AND shop_name = ? AND del_flag = 0
                """);
        if (categoryL1 != null && !categoryL1.isEmpty()) {
            sql.append(" AND category_l1 = ?");
            params.add(categoryL1);
        }
        sql.append(" ORDER BY gmv_usd DESC NULLS LAST, sales_volume DESC NULLS LAST, id ASC");
        sql.append(" LIMIT ?");
        params.add(Math.max(1, limit));
        return jdbcTemplate.query(sql.toString(), PRODUCT_ROW_MAPPER, params.toArray());
    }

    /** Total active product rows across all snapshots (for startup memory guard). */
    public long countAllProducts() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rank_product WHERE del_flag = 0", Long.class);
        return n == null ? 0L : n;
    }

    /**
     * Wipe all ranking data. Used when the board is oversized and pressures free-tier memory.
     * @return deleted product row count (best-effort)
     */
    public int clearAll() {
        Integer productCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rank_product", Integer.class);
        jdbcTemplate.update("DELETE FROM rank_product");
        jdbcTemplate.update("DELETE FROM rank_snapshot");
        int deleted = productCount == null ? 0 : productCount;
        log.warn("Cleared all ranking data: deletedProducts={}", deleted);
        return deleted;
    }

    /**
     * Keep at most {@code limit} products globally (highest GMV), delete the rest.
     * Snapshots with zero remaining products are removed.
     */
    public int pruneToGlobalLimit(int limit) {
        int keep = Math.max(1, limit);
        int deleted = jdbcTemplate.update(
                """
                DELETE FROM rank_product
                WHERE id NOT IN (
                  SELECT id FROM (
                    SELECT id
                    FROM rank_product
                    WHERE del_flag = 0
                    ORDER BY gmv_usd DESC NULLS LAST, sales_volume DESC NULLS LAST, id ASC
                    LIMIT ?
                  ) keepers
                )
                """,
                keep);
        jdbcTemplate.update(
                """
                DELETE FROM rank_snapshot s
                WHERE NOT EXISTS (
                  SELECT 1 FROM rank_product p
                  WHERE p.snapshot_id = s.id AND p.del_flag = 0
                )
                """);
        jdbcTemplate.update(
                """
                UPDATE rank_snapshot s
                SET product_count = (
                  SELECT COUNT(*) FROM rank_product p
                  WHERE p.snapshot_id = s.id AND p.del_flag = 0
                ),
                updated_at = NOW()
                """);
        log.warn("Pruned ranking products to global limit {}: deleted={}", keep, deleted);
        return deleted;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
