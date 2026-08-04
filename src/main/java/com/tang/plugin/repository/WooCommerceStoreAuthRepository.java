package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.user.WooCommerceStoreAuth;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class WooCommerceStoreAuthRepository {

    private static final RowMapper<WooCommerceStoreAuth> ROW_MAPPER = (rs, rowNum) -> {
        WooCommerceStoreAuth row = new WooCommerceStoreAuth()
                .setId(rs.getLong("id"))
                .setUserId(rs.getObject("user_id", Long.class))
                .setShopName(rs.getString("shop_name"))
                .setSiteUrl(rs.getString("site_url"))
                .setConsumerKey(rs.getString("consumer_key"))
                .setConsumerSecret(rs.getString("consumer_secret"))
                .setKeyPermissions(rs.getString("key_permissions"))
                .setKeyId(rs.getString("key_id"))
                .setAuthStatus(rs.getInt("auth_status"))
                .setStatus(rs.getInt("status"))
                .setApiVersion(rs.getString("api_version"))
                .setSslEnabled(rs.getBoolean("ssl_enabled"))
                .setWebhookSecret(rs.getString("webhook_secret"))
                .setDelFlag(rs.getInt("del_flag"))
                .setRemark(rs.getString("remark"));
        Timestamp lastSync = rs.getTimestamp("last_sync_time");
        Timestamp created = rs.getTimestamp("create_time");
        Timestamp updated = rs.getTimestamp("update_time");
        row.setLastSyncTime(lastSync == null ? null : lastSync.toInstant());
        row.setCreateTime(created == null ? null : created.toInstant());
        row.setUpdateTime(updated == null ? null : updated.toInstant());
        return row;
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    public Optional<WooCommerceStoreAuth> findByShopName(String shopName) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    SELECT id, user_id, shop_name, site_url, consumer_key, consumer_secret,
                           key_permissions, key_id, auth_status, status, api_version,
                           ssl_enabled, webhook_secret, last_sync_time, create_time,
                           update_time, del_flag, remark
                    FROM woocommerce_store_auth
                    WHERE shop_name = ? AND del_flag = 0
                    """,
                    ROW_MAPPER, shopName));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Long upsertActive(WooCommerceStoreAuth auth) {
        Instant now = Instant.now();
        Optional<WooCommerceStoreAuth> existing = findByShopName(auth.getShopName());
        if (existing.isPresent()) {
            Long id = existing.get().getId();
            jdbcTemplate.update(
                    """
                    UPDATE woocommerce_store_auth
                    SET user_id = ?, site_url = ?, consumer_key = ?, consumer_secret = ?,
                        key_permissions = ?, key_id = ?, auth_status = 1, status = 1,
                        api_version = 'v3', ssl_enabled = TRUE, update_time = ?,
                        del_flag = 0, remark = ?
                    WHERE id = ?
                    """,
                    auth.getUserId(), auth.getSiteUrl(), auth.getConsumerKey(),
                    auth.getConsumerSecret(), auth.getKeyPermissions(), auth.getKeyId(),
                    Timestamp.from(now), auth.getRemark(), id);
            return id;
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    """
                    INSERT INTO woocommerce_store_auth
                    (user_id, shop_name, site_url, consumer_key, consumer_secret,
                     key_permissions, key_id, auth_status, status, api_version,
                     ssl_enabled, create_time, update_time, del_flag, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, 'v3', TRUE, ?, ?, 0, ?)
                    """,
                    new String[]{"id"});
            ps.setObject(1, auth.getUserId());
            ps.setString(2, auth.getShopName());
            ps.setString(3, auth.getSiteUrl());
            ps.setString(4, auth.getConsumerKey());
            ps.setString(5, auth.getConsumerSecret());
            ps.setString(6, auth.getKeyPermissions());
            ps.setString(7, auth.getKeyId());
            ps.setTimestamp(8, Timestamp.from(now));
            ps.setTimestamp(9, Timestamp.from(now));
            ps.setString(10, auth.getRemark());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to retrieve generated id for woocommerce_store_auth");
        }
        return key.longValue();
    }
}
