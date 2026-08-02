package com.tang.plugin.repository.bundle;

import com.tang.plugin.domain.entity.bundle.ShopBundleCampaign;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
public class ShopBundleCampaignRepository {

    private static final String COLUMNS = """
            id, shop_name, play_type, title, status, rule_json, pool_json, shopify_refs_json,
            linked_bundle_id, del_flag, created_at, updated_at
            """;

    private static final RowMapper<ShopBundleCampaign> ROW_MAPPER = (rs, rowNum) -> new ShopBundleCampaign()
            .setId(rs.getString("id"))
            .setShopName(rs.getString("shop_name"))
            .setPlayType(rs.getString("play_type"))
            .setTitle(rs.getString("title"))
            .setStatus(rs.getString("status"))
            .setRuleJson(rs.getString("rule_json"))
            .setPoolJson(rs.getString("pool_json"))
            .setShopifyRefsJson(rs.getString("shopify_refs_json"))
            .setLinkedBundleId(rs.getObject("linked_bundle_id") == null ? null : rs.getLong("linked_bundle_id"))
            .setDelFlag(rs.getInt("del_flag"))
            .setCreatedAt(toInstant(rs.getTimestamp("created_at")))
            .setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));

    @Resource
    private JdbcTemplate jdbcTemplate;

    public Optional<ShopBundleCampaign> findById(String id) {
        if (StringUtils.isBlank(id)) return Optional.empty();
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM shop_bundle_campaign WHERE id = ? AND del_flag = 0",
                    ROW_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<ShopBundleCampaign> listByShop(String shopName) {
        if (StringUtils.isBlank(shopName)) return Collections.emptyList();
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM shop_bundle_campaign "
                        + "WHERE shop_name = ? AND del_flag = 0 "
                        + "AND status IN ('ACTIVE','DRAFT','ARCHIVED') "
                        + "ORDER BY updated_at DESC",
                ROW_MAPPER, shopName);
    }

    public void insert(ShopBundleCampaign row) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO shop_bundle_campaign ("
                        + "id, shop_name, play_type, title, status, rule_json, pool_json, shopify_refs_json, "
                        + "linked_bundle_id, del_flag, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,0,?,?)",
                row.getId(),
                row.getShopName(),
                row.getPlayType(),
                row.getTitle(),
                row.getStatus(),
                row.getRuleJson(),
                row.getPoolJson(),
                row.getShopifyRefsJson(),
                row.getLinkedBundleId(),
                Timestamp.from(now),
                Timestamp.from(now));
        row.setCreatedAt(now).setUpdatedAt(now).setDelFlag(0);
    }

    public void update(ShopBundleCampaign row) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "UPDATE shop_bundle_campaign SET title = ?, status = ?, rule_json = ?, pool_json = ?, "
                        + "shopify_refs_json = ?, linked_bundle_id = ?, updated_at = ? "
                        + "WHERE id = ? AND shop_name = ? AND del_flag = 0",
                row.getTitle(),
                row.getStatus(),
                row.getRuleJson(),
                row.getPoolJson(),
                row.getShopifyRefsJson(),
                row.getLinkedBundleId(),
                Timestamp.from(now),
                row.getId(),
                row.getShopName());
        row.setUpdatedAt(now);
    }

    public void softDelete(String shopName, String id) {
        jdbcTemplate.update(
                "UPDATE shop_bundle_campaign SET del_flag = 1, status = 'ARCHIVED', updated_at = ? "
                        + "WHERE id = ? AND shop_name = ? AND del_flag = 0",
                Timestamp.from(Instant.now()), id, shopName);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
