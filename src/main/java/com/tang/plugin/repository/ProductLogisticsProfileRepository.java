package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.logistics.ProductLogisticsProfile;
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

@Slf4j
@Repository
public class ProductLogisticsProfileRepository {

    private static final RowMapper<ProductLogisticsProfile> ROW_MAPPER = (rs, rowNum) ->
            new ProductLogisticsProfile()
                    .setId(rs.getLong("id"))
                    .setShopName(rs.getString("shop_name"))
                    .setThirdPlatformItemId(rs.getString("third_platform_item_id"))
                    .setTitleSnapshot(rs.getString("title_snapshot"))
                    .setLogisticsType(rs.getString("logistics_type"))
                    .setConfidence((Double) rs.getObject("confidence"))
                    .setSignalsJson(rs.getString("signals_json"))
                    .setClassifySource(rs.getString("classify_source"))
                    .setReviewed(rs.getInt("reviewed"))
                    .setDelFlag(rs.getInt("del_flag"))
                    .setCreatedAt(toInstant(rs.getTimestamp("created_at")))
                    .setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));

    private static final String COLUMNS = """
            id, shop_name, third_platform_item_id, title_snapshot, logistics_type, confidence,
            signals_json, classify_source, reviewed, del_flag, created_at, updated_at
            """;

    @Resource
    private JdbcTemplate jdbcTemplate;

    public Optional<ProductLogisticsProfile> findByItem(String shopName, String itemId) {
        if (StringUtils.isAnyBlank(shopName, itemId)) {
            return Optional.empty();
        }
        try {
            ProductLogisticsProfile row = jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM product_logistics_profile "
                            + "WHERE shop_name = ? AND third_platform_item_id = ? AND del_flag = 0",
                    ROW_MAPPER, shopName, itemId);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<ProductLogisticsProfile> listByShop(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            return Collections.emptyList();
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM product_logistics_profile "
                        + "WHERE shop_name = ? AND del_flag = 0 ORDER BY id DESC",
                ROW_MAPPER, shopName);
    }

    public ProductLogisticsProfile upsert(ProductLogisticsProfile profile) {
        Instant now = Instant.now();
        Optional<ProductLogisticsProfile> existing =
                findByItem(profile.getShopName(), profile.getThirdPlatformItemId());
        if (existing.isPresent()) {
            Long id = existing.get().getId();
            jdbcTemplate.update(
                    """
                    UPDATE product_logistics_profile
                    SET title_snapshot = ?, logistics_type = ?, confidence = ?, signals_json = ?,
                        classify_source = ?, reviewed = ?, updated_at = ?, del_flag = 0
                    WHERE id = ?
                    """,
                    profile.getTitleSnapshot(),
                    profile.getLogisticsType(),
                    profile.getConfidence(),
                    profile.getSignalsJson(),
                    profile.getClassifySource(),
                    profile.getReviewed() == null ? 0 : profile.getReviewed(),
                    Timestamp.from(now),
                    id);
            return findByItem(profile.getShopName(), profile.getThirdPlatformItemId()).orElseThrow();
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO product_logistics_profile
                    (shop_name, third_platform_item_id, title_snapshot, logistics_type, confidence,
                     signals_json, classify_source, reviewed, del_flag, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """,
                    new String[]{"id"});
            ps.setString(1, profile.getShopName());
            ps.setString(2, profile.getThirdPlatformItemId());
            ps.setString(3, profile.getTitleSnapshot());
            ps.setString(4, profile.getLogisticsType());
            ps.setObject(5, profile.getConfidence());
            ps.setString(6, profile.getSignalsJson());
            ps.setString(7, profile.getClassifySource());
            ps.setInt(8, profile.getReviewed() == null ? 0 : profile.getReviewed());
            ps.setTimestamp(9, Timestamp.from(now));
            ps.setTimestamp(10, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return findByItem(profile.getShopName(), profile.getThirdPlatformItemId()).orElseThrow();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
