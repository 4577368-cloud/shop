package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.user.ShopifyStoreAuth;
import com.tang.plugin.enums.ShopifyAuthStatus;
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
import java.util.List;
import java.util.Optional;

@Slf4j
@Repository
public class ShopifyStoreAuthRepository {

    private static final RowMapper<ShopifyStoreAuth> ROW_MAPPER = (rs, rowNum) -> new ShopifyStoreAuth()
            .setId(rs.getLong("id"))
            .setShopName(rs.getString("shop_name"))
            .setShopDomain(rs.getString("shop_domain"))
            .setAccessToken(rs.getString("access_token"))
            .setScope(rs.getString("scope"))
            .setStatus(ShopifyAuthStatus.valueOf(rs.getString("status")))
            .setAuthorizedAt(toInstant(rs.getTimestamp("authorized_at")))
            .setUpdatedAt(toInstant(rs.getTimestamp("updated_at")))
            .setDelFlag(rs.getInt("del_flag"));

    @Resource
    private JdbcTemplate jdbcTemplate;

    public Optional<ShopifyStoreAuth> findByShopDomain(String shopDomain) {
        if (StringUtils.isBlank(shopDomain)) {
            return Optional.empty();
        }
        try {
            ShopifyStoreAuth auth = jdbcTemplate.queryForObject(
                    """
                    SELECT id, shop_name, shop_domain, access_token, scope, status,
                           authorized_at, updated_at, del_flag
                    FROM shopify_store_auth
                    WHERE shop_domain = ?
                    """,
                    ROW_MAPPER,
                    shopDomain.trim().toLowerCase());
            return Optional.ofNullable(auth);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<ShopifyStoreAuth> findActiveByShopDomain(String shopDomain) {
        return findByShopDomain(shopDomain)
                .filter(auth -> auth.getDelFlag() != null && auth.getDelFlag() == 0)
                .filter(auth -> auth.getStatus() == ShopifyAuthStatus.ACTIVE);
    }

    public Optional<ShopifyStoreAuth> findActiveByShopName(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            return Optional.empty();
        }
        try {
            ShopifyStoreAuth auth = jdbcTemplate.queryForObject(
                    """
                    SELECT id, shop_name, shop_domain, access_token, scope, status,
                           authorized_at, updated_at, del_flag
                    FROM shopify_store_auth
                    WHERE shop_name = ? AND status = ? AND del_flag = 0
                    """,
                    ROW_MAPPER,
                    shopName.trim(),
                    ShopifyAuthStatus.ACTIVE.name());
            return Optional.ofNullable(auth);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<ShopifyStoreAuth> listActive() {
        return jdbcTemplate.query(
                """
                SELECT id, shop_name, shop_domain, access_token, scope, status,
                       authorized_at, updated_at, del_flag
                FROM shopify_store_auth
                WHERE status = ? AND del_flag = 0
                """,
                ROW_MAPPER,
                ShopifyAuthStatus.ACTIVE.name());
    }

    public Long upsertActive(ShopifyStoreAuth auth) {
        Instant now = Instant.now();
        Optional<ShopifyStoreAuth> existing = findByShopDomain(auth.getShopDomain());
        if (existing.isPresent()) {
            Long id = existing.get().getId();
            jdbcTemplate.update(
                    """
                    UPDATE shopify_store_auth
                    SET shop_name = ?, access_token = ?, scope = ?, status = ?,
                        authorized_at = ?, updated_at = ?, del_flag = 0
                    WHERE id = ?
                    """,
                    auth.getShopName(),
                    auth.getAccessToken(),
                    auth.getScope(),
                    ShopifyAuthStatus.ACTIVE.name(),
                    Timestamp.from(auth.getAuthorizedAt() != null ? auth.getAuthorizedAt() : now),
                    Timestamp.from(now),
                    id);
            log.info("Updated ShopifyStoreAuth shopDomain={} shopName={} id={}",
                    auth.getShopDomain(), auth.getShopName(), id);
            return id;
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO shopify_store_auth
                    (shop_name, shop_domain, access_token, scope, status, authorized_at, updated_at, del_flag)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                    """,
                    new String[]{"id"});
            ps.setString(1, auth.getShopName());
            ps.setString(2, auth.getShopDomain().toLowerCase());
            ps.setString(3, auth.getAccessToken());
            ps.setString(4, auth.getScope());
            ps.setString(5, ShopifyAuthStatus.ACTIVE.name());
            Instant authorizedAt = auth.getAuthorizedAt() != null ? auth.getAuthorizedAt() : now;
            ps.setTimestamp(6, Timestamp.from(authorizedAt));
            ps.setTimestamp(7, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        Long id = key == null ? null : key.longValue();
        log.info("Inserted ShopifyStoreAuth shopDomain={} shopName={} id={}",
                auth.getShopDomain(), auth.getShopName(), id);
        return id;
    }

    public void markUninstalled(String shopDomain) {
        Instant now = Instant.now();
        int updated = jdbcTemplate.update(
                """
                UPDATE shopify_store_auth
                SET status = ?, access_token = '', updated_at = ?, del_flag = 1
                WHERE shop_domain = ?
                """,
                ShopifyAuthStatus.UNINSTALLED.name(),
                Timestamp.from(now),
                shopDomain.trim().toLowerCase());
        log.info("Mark UNINSTALLED shopDomain={} rows={}", shopDomain, updated);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
