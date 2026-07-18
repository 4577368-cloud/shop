package com.tang.plugin.repository;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.entity.match.ShopProductMatchCandidate;
import com.tang.plugin.enums.match.MatchSource;
import com.tang.plugin.enums.match.MatchStatus;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC repository for {@code shop_product_match_candidate}. SKU-level only:
 * natural key (shop_name, third_platform_sku_id, tangbuy_sku_id, match_source) has no nullable
 * columns, so the DB unique index is the real idempotency guarantee. Status/soft-delete only.
 */
@Slf4j
@Repository
public class ShopProductMatchCandidateRepository {

    private static final RowMapper<ShopProductMatchCandidate> ROW_MAPPER = (rs, rowNum) -> new ShopProductMatchCandidate()
            .setId(rs.getLong("id"))
            .setShopName(rs.getString("shop_name"))
            .setShopType(rs.getString("shop_type"))
            .setThirdPlatformItemId(rs.getString("third_platform_item_id"))
            .setThirdPlatformSkuId(rs.getString("third_platform_sku_id"))
            .setTangbuyProductId(rs.getString("tangbuy_product_id"))
            .setTangbuySkuId(rs.getString("tangbuy_sku_id"))
            .setMatchSource(MatchSource.valueOf(rs.getString("match_source")))
            .setMatchScore(rs.getBigDecimal("match_score"))
            .setMatchReason(rs.getString("match_reason"))
            .setStatus(MatchStatus.valueOf(rs.getString("status")))
            .setDelFlag(rs.getInt("del_flag"))
            .setCreatedAt(toInstant(rs.getTimestamp("created_at")))
            .setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));

    private static final String COLUMNS = """
            id, shop_name, shop_type, third_platform_item_id, third_platform_sku_id,
            tangbuy_product_id, tangbuy_sku_id, match_source, match_score, match_reason,
            status, del_flag, created_at, updated_at
            """;

    @Resource
    private JdbcTemplate jdbcTemplate;

    public Optional<ShopProductMatchCandidate> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        try {
            ShopProductMatchCandidate candidate = jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM shop_product_match_candidate WHERE id = ?",
                    ROW_MAPPER, id);
            return Optional.ofNullable(candidate);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<ShopProductMatchCandidate> listByShopAndStatus(String shopName, MatchStatus status) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM shop_product_match_candidate "
                        + "WHERE shop_name = ? AND status = ? AND del_flag = 0 ORDER BY id DESC",
                ROW_MAPPER, shopName, status.name());
    }

    /**
     * Upsert candidate keyed by (shop_name, third_platform_sku_id, tangbuy_sku_id, match_source).
     * Returns the row id.
     */
    public Long upsert(ShopProductMatchCandidate candidate) {
        requireSkuLevel(candidate);
        Instant now = Instant.now();
        String matchKey = naturalKey(candidate);
        Optional<Long> existingId = findIdByNaturalKey(candidate);
        log.info("Candidate upsert matchKey={} existing={}", matchKey, existingId.orElse(null));
        if (existingId.isPresent()) {
            Long id = existingId.get();
            jdbcTemplate.update(
                    """
                    UPDATE shop_product_match_candidate
                    SET shop_type = ?, third_platform_item_id = ?, tangbuy_product_id = ?,
                        match_score = ?, match_reason = ?, status = ?, del_flag = 0, updated_at = ?
                    WHERE id = ?
                    """,
                    candidate.getShopType(),
                    candidate.getThirdPlatformItemId(),
                    candidate.getTangbuyProductId(),
                    candidate.getMatchScore(),
                    candidate.getMatchReason(),
                    candidate.getStatus().name(),
                    Timestamp.from(now),
                    id);
            candidate.setId(id);
            log.info("Candidate updated id={} shopName={} thirdPlatformSkuId={}",
                    id, candidate.getShopName(), candidate.getThirdPlatformSkuId());
            return id;
        }
        jdbcTemplate.update(
                """
                INSERT INTO shop_product_match_candidate
                (shop_name, shop_type, third_platform_item_id, third_platform_sku_id, tangbuy_product_id,
                 tangbuy_sku_id, match_source, match_score, match_reason, status, del_flag, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """,
                candidate.getShopName(),
                candidate.getShopType(),
                candidate.getThirdPlatformItemId(),
                candidate.getThirdPlatformSkuId(),
                candidate.getTangbuyProductId(),
                candidate.getTangbuySkuId(),
                candidate.getMatchSource().name(),
                candidate.getMatchScore(),
                candidate.getMatchReason(),
                candidate.getStatus().name(),
                Timestamp.from(now),
                Timestamp.from(now));
        Long id = findIdByNaturalKey(candidate).orElse(null);
        candidate.setId(id);
        log.info("Candidate inserted id={} shopName={} thirdPlatformSkuId={} source={}",
                id, candidate.getShopName(), candidate.getThirdPlatformSkuId(), candidate.getMatchSource());
        return id;
    }

    public void updateStatus(Long id, MatchStatus status) {
        jdbcTemplate.update(
                "UPDATE shop_product_match_candidate SET status = ?, updated_at = ? WHERE id = ?",
                status.name(), Timestamp.from(Instant.now()), id);
        log.info("Candidate status updated id={} status={}", id, status);
    }

    private Optional<Long> findIdByNaturalKey(ShopProductMatchCandidate candidate) {
        try {
            Long id = jdbcTemplate.queryForObject(
                    """
                    SELECT id FROM shop_product_match_candidate
                    WHERE shop_name = ?
                      AND match_source = ?
                      AND third_platform_sku_id = ?
                      AND tangbuy_sku_id = ?
                    """,
                    Long.class,
                    candidate.getShopName(),
                    candidate.getMatchSource().name(),
                    candidate.getThirdPlatformSkuId(),
                    candidate.getTangbuySkuId());
            return Optional.ofNullable(id);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private void requireSkuLevel(ShopProductMatchCandidate candidate) {
        if (candidate == null
                || StringUtils.isAnyBlank(candidate.getShopName(), candidate.getThirdPlatformItemId(),
                candidate.getThirdPlatformSkuId(), candidate.getTangbuySkuId())
                || candidate.getMatchSource() == null) {
            throw new CustomException("Candidate must be SKU-level: shopName/itemId/thirdPlatformSkuId/"
                    + "tangbuySkuId/matchSource are required");
        }
    }

    private static String naturalKey(ShopProductMatchCandidate candidate) {
        return candidate.getShopName() + "|" + candidate.getMatchSource()
                + "|" + candidate.getThirdPlatformSkuId() + "|" + candidate.getTangbuySkuId();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
