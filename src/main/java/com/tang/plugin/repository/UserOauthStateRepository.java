package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.user.UserOauthState;
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
import java.util.Optional;

/**
 * Repository for the {@code user_oauth_state} table.
 *
 * <p>Lifecycle: created by {@code buildInstallUrl} (state hash + userId + shopDomain + 10min TTL),
 * looked up & marked consumed by {@code handleCallback}, hard-deleted by a future cleanup job.
 *
 * <p>State is stored as SHA-256 hash (never plaintext) so a DB leak does not reveal
 * valid states. The raw state is only ever sent to Shopify and round-tripped back.
 */
@Slf4j
@Repository
public class UserOauthStateRepository {

    private static final RowMapper<UserOauthState> ROW_MAPPER = (rs, rowNum) -> {
        UserOauthState s = new UserOauthState()
                .setId(rs.getLong("id"))
                .setStateHash(rs.getString("state_hash"))
                .setUserId(rs.getLong("user_id"))
                .setShopDomain(rs.getString("shop_domain"));
        Timestamp exp = rs.getTimestamp("expires_at");
        s.setExpiresAt(exp != null ? exp.toInstant() : null);
        Timestamp consumed = rs.getTimestamp("consumed_at");
        s.setConsumedAt(consumed != null ? consumed.toInstant() : null);
        Timestamp created = rs.getTimestamp("created_at");
        s.setCreatedAt(created != null ? created.toInstant() : null);
        return s;
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    public UserOauthState insert(String stateHash, Long userId, String shopDomain, Instant expiresAt) {
        String sql = """
                INSERT INTO user_oauth_state (state_hash, user_id, shop_domain, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        Instant now = Instant.now();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, stateHash);
            ps.setLong(2, userId);
            ps.setString(3, shopDomain);
            ps.setTimestamp(4, Timestamp.from(expiresAt));
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to retrieve generated id for user_oauth_state insert");
        }
        return new UserOauthState()
                .setId(key.longValue())
                .setStateHash(stateHash)
                .setUserId(userId)
                .setShopDomain(shopDomain)
                .setExpiresAt(expiresAt)
                .setCreatedAt(now);
    }

    /**
     * Look up an unconsumed, unexpired state by hash. Returns empty if not found,
     * already consumed, or expired. Caller is responsible for marking it consumed
     * after a successful callback to prevent replay.
     */
    public Optional<UserOauthState> findActiveByStateHash(String stateHash) {
        if (stateHash == null || stateHash.isBlank()) return Optional.empty();
        try {
            UserOauthState s = jdbcTemplate.queryForObject(
                    """
                    SELECT id, state_hash, user_id, shop_domain, expires_at, consumed_at, created_at
                    FROM user_oauth_state
                    WHERE state_hash = ? AND consumed_at IS NULL AND expires_at > ?
                    """,
                    ROW_MAPPER, stateHash, Timestamp.from(Instant.now()));
            return Optional.ofNullable(s);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Mark a state as consumed (one-time use). Uses optimistic compare-and-set on
     * consumed_at IS NULL so concurrent callbacks cannot double-consume.
     * Returns true if the row was actually updated (1 row affected).
     */
    public boolean markConsumed(Long id) {
        int affected = jdbcTemplate.update(
                """
                UPDATE user_oauth_state
                SET consumed_at = ?
                WHERE id = ? AND consumed_at IS NULL
                """,
                Timestamp.from(Instant.now()), id);
        return affected == 1;
    }

    /**
     * Hard-delete expired states. Intended to be called by a scheduled job (future)
     * or manually. Returns the number of deleted rows.
     */
    public int deleteExpired() {
        return jdbcTemplate.update(
                "DELETE FROM user_oauth_state WHERE expires_at < ?",
                Timestamp.from(Instant.now()));
    }
}
