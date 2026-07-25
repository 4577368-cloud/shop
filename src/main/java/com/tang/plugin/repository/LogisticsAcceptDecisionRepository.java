package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.logistics.LogisticsAcceptDecision;
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
 * 物流接受决策持久化层。
 *
 * <p>UPSERT 模式：以 (shop_name, third_platform_sku_id) 为自然键，
 * 命中则覆盖线路字段 + 更新 accepted_at，未命中则插入。与前端
 * {@code upsertAcceptances} 的 Map 合并语义一致。
 */
@Slf4j
@Repository
public class LogisticsAcceptDecisionRepository {

    private static final RowMapper<LogisticsAcceptDecision> ROW_MAPPER = (rs, rowNum) ->
            new LogisticsAcceptDecision()
                    .setId(rs.getLong("id"))
                    .setShopName(rs.getString("shop_name"))
                    .setThirdPlatformItemId(rs.getString("third_platform_item_id"))
                    .setThirdPlatformSkuId(rs.getString("third_platform_sku_id"))
                    .setQuoteStatus(rs.getString("quote_status"))
                    .setRecommendedLineJson(rs.getString("recommended_line_json"))
                    .setAlternativeLinesJson(rs.getString("alternative_lines_json"))
                    .setAcceptedAt(toInstant(rs.getTimestamp("accepted_at")))
                    .setDelFlag(rs.getInt("del_flag"))
                    .setCreatedAt(toInstant(rs.getTimestamp("created_at")))
                    .setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));

    private static final String COLUMNS = """
            id, shop_name, third_platform_item_id, third_platform_sku_id, quote_status,
            recommended_line_json, alternative_lines_json, accepted_at, del_flag,
            created_at, updated_at
            """;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 按 shop 全量查询未删除的决策。 */
    public List<LogisticsAcceptDecision> listByShop(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            return Collections.emptyList();
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM logistics_accept_decision "
                        + "WHERE shop_name = ? AND del_flag = 0 ORDER BY id DESC",
                ROW_MAPPER, shopName);
    }

    /** 按 (shop, skuId) 查询单条，用于 UPSERT 前的存在性检查。 */
    public Optional<LogisticsAcceptDecision> findByShopAndSku(String shopName, String skuId) {
        if (StringUtils.isAnyBlank(shopName, skuId)) {
            return Optional.empty();
        }
        try {
            LogisticsAcceptDecision row = jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM logistics_accept_decision "
                            + "WHERE shop_name = ? AND third_platform_sku_id = ? AND del_flag = 0",
                    ROW_MAPPER, shopName, skuId);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 按 (shop, skuId) 软删除。 */
    public int softDelete(String shopName, String skuId) {
        if (StringUtils.isAnyBlank(shopName, skuId)) {
            return 0;
        }
        return jdbcTemplate.update(
                "UPDATE logistics_accept_decision SET del_flag = 1, updated_at = ? "
                        + "WHERE shop_name = ? AND third_platform_sku_id = ? AND del_flag = 0",
                Timestamp.from(Instant.now()), shopName, skuId);
    }

    /**
     * UPSERT 单条决策。命中则覆盖线路三字段 + quote_status + accepted_at + updated_at，
     * 未命中则插入新记录。
     */
    public LogisticsAcceptDecision upsert(LogisticsAcceptDecision record) {
        Instant now = Instant.now();
        Optional<LogisticsAcceptDecision> existing =
                findByShopAndSku(record.getShopName(), record.getThirdPlatformSkuId());
        if (existing.isPresent()) {
            Long id = existing.get().getId();
            jdbcTemplate.update(
                    """
                    UPDATE logistics_accept_decision
                    SET third_platform_item_id = ?, quote_status = ?, recommended_line_json = ?,
                        alternative_lines_json = ?, accepted_at = ?, updated_at = ?, del_flag = 0
                    WHERE id = ?
                    """,
                    record.getThirdPlatformItemId(),
                    record.getQuoteStatus(),
                    record.getRecommendedLineJson(),
                    record.getAlternativeLinesJson(),
                    Timestamp.from(record.getAcceptedAt() != null ? record.getAcceptedAt() : now),
                    Timestamp.from(now),
                    id);
            return findByShopAndSku(record.getShopName(), record.getThirdPlatformSkuId())
                    .orElseThrow();
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        Instant acceptedAt = record.getAcceptedAt() != null ? record.getAcceptedAt() : now;
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO logistics_accept_decision
                    (shop_name, third_platform_item_id, third_platform_sku_id, quote_status,
                     recommended_line_json, alternative_lines_json, accepted_at, del_flag,
                     created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """,
                    new String[]{"id"});
            ps.setString(1, record.getShopName());
            ps.setString(2, record.getThirdPlatformItemId());
            ps.setString(3, record.getThirdPlatformSkuId());
            ps.setString(4, record.getQuoteStatus());
            ps.setString(5, record.getRecommendedLineJson());
            ps.setString(6, record.getAlternativeLinesJson());
            ps.setTimestamp(7, Timestamp.from(acceptedAt));
            ps.setTimestamp(8, Timestamp.from(now));
            ps.setTimestamp(9, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return findByShopAndSku(record.getShopName(), record.getThirdPlatformSkuId())
                .orElseThrow();
    }

    /**
     * Patch 线路字段：只覆盖 quote_status / recommended_line_json / alternative_lines_json，
     * 保留 accepted_at 不变（与前端 patch-quotes 路由语义一致）。
     */
    public Optional<LogisticsAcceptDecision> patchQuotes(
            String shopName, String skuId, String quoteStatus,
            String recommendedLineJson, String alternativeLinesJson) {
        Optional<LogisticsAcceptDecision> existing = findByShopAndSku(shopName, skuId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        Long id = existing.get().getId();
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                UPDATE logistics_accept_decision
                SET quote_status = ?, recommended_line_json = ?, alternative_lines_json = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                quoteStatus,
                recommendedLineJson,
                alternativeLinesJson,
                Timestamp.from(now),
                id);
        return findByShopAndSku(shopName, skuId);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
