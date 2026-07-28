package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.user.UserShop;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
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

/**
 * Repository for the {@code user_shop} binding table.
 *
 * <p>Unbind is a physical DELETE (not soft-delete) — the table is a pure junction,
 * and the audit trail lives in {@code shopify_store_auth}. Re-bind after unbind
 * reuses the row via the UPSERT in {@link #upsertBinding}.
 */
@Slf4j
@Repository
public class UserShopRepository {

    private static final RowMapper<UserShop> ROW_MAPPER = (rs, rowNum) -> {
        UserShop s = new UserShop()
                .setId(rs.getLong("id"))
                .setUserId(rs.getLong("user_id"))
                .setShopName(rs.getString("shop_name"))
                .setShopDomain(rs.getString("shop_domain"))
                .setRole(rs.getString("role"));
        Timestamp bound = rs.getTimestamp("bound_at");
        s.setBoundAt(bound != null ? bound.toInstant() : null);
        Timestamp created = rs.getTimestamp("created_at");
        s.setCreatedAt(created != null ? created.toInstant() : null);
        Timestamp updated = rs.getTimestamp("updated_at");
        s.setUpdatedAt(updated != null ? updated.toInstant() : null);
        return s;
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * Insert or revive a binding. If a row already exists for (userId, shopName),
     * reset it to active state (rebind after unbind reuses the row because the
     * unique index covers it). Returns the persisted row.
     *
     * <p>Uses SELECT + INSERT/UPDATE (not MERGE INTO or ON CONFLICT) for H2 + PostgreSQL
     * compatibility, matching the pattern in {@code ShopifyStoreAuthRepository.upsertActive}.
     */
    public UserShop upsertBinding(Long userId, String shopName, String shopDomain) {
        Instant now = Instant.now();
        Optional<UserShop> existing = findByUserIdAndShopName(userId, shopName);
        if (existing.isPresent()) {
            UserShop row = existing.get();
            jdbcTemplate.update(
                    """
                    UPDATE user_shop
                    SET shop_domain = ?, role = 'owner', bound_at = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    shopDomain, Timestamp.from(now), Timestamp.from(now), row.getId());
            row.setShopDomain(shopDomain);
            row.setRole("owner");
            row.setBoundAt(now);
            row.setUpdatedAt(now);
            return row;
        }
        String sql = """
                INSERT INTO user_shop (user_id, shop_name, shop_domain, role, bound_at, created_at, updated_at)
                VALUES (?, ?, ?, 'owner', ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setLong(1, userId);
            ps.setString(2, shopName);
            ps.setString(3, shopDomain);
            ps.setTimestamp(4, Timestamp.from(now));
            ps.setTimestamp(5, Timestamp.from(now));
            ps.setTimestamp(6, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to retrieve generated id for user_shop insert");
        }
        UserShop row = new UserShop()
                .setId(key.longValue())
                .setUserId(userId)
                .setShopName(shopName)
                .setShopDomain(shopDomain)
                .setRole("owner")
                .setBoundAt(now)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        return row;
    }

    public Optional<UserShop> findByUserIdAndShopName(Long userId, String shopName) {
        if (userId == null || shopName == null || shopName.isBlank()) return Optional.empty();
        try {
            UserShop s = jdbcTemplate.queryForObject(
                    """
                    SELECT id, user_id, shop_name, shop_domain, role, bound_at, created_at, updated_at
                    FROM user_shop
                    WHERE user_id = ? AND shop_name = ?
                    """,
                    ROW_MAPPER, userId, shopName);
            return Optional.ofNullable(s);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<UserShop> listByUserId(Long userId) {
        if (userId == null) return List.of();
        return jdbcTemplate.query(
                """
                SELECT id, user_id, shop_name, shop_domain, role, bound_at, created_at, updated_at
                FROM user_shop
                WHERE user_id = ?
                ORDER BY bound_at DESC, id DESC
                """,
                ROW_MAPPER, userId);
    }

    /**
     * Look up the owner of a shop. Used by business queries to verify that the
     * requesting user actually owns the shop_name they are passing as a filter.
     */
    public Optional<UserShop> findByShopName(String shopName) {
        if (shopName == null || shopName.isBlank()) return Optional.empty();
        try {
            UserShop s = jdbcTemplate.queryForObject(
                    """
                    SELECT id, user_id, shop_name, shop_domain, role, bound_at, created_at, updated_at
                    FROM user_shop
                    WHERE shop_name = ?
                    """,
                    ROW_MAPPER, shopName);
            return Optional.ofNullable(s);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Physical delete. Used when the user unbinds a shop. Returns the number of
     * affected rows (0 = nothing to unbind).
     */
    public int deleteByUserIdAndShopName(Long userId, String shopName) {
        return jdbcTemplate.update(
                "DELETE FROM user_shop WHERE user_id = ? AND shop_name = ?",
                userId, shopName);
    }

    /** GDPR shop/redact — remove all Tangbuy↔shop bindings for this shop. */
    public int deleteByShopName(String shopName) {
        if (shopName == null || shopName.isBlank()) {
            return 0;
        }
        return jdbcTemplate.update("DELETE FROM user_shop WHERE shop_name = ?", shopName.trim());
    }

    /**
     * Count active bindings for a user. Used to decide whether to show the
     * "bind your first shop" empty state.
     */
    public int countByUserId(Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_shop WHERE user_id = ?",
                Integer.class, userId);
        return count != null ? count : 0;
    }
}
