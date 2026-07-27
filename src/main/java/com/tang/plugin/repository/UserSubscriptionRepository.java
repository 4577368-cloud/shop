package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.user.UserSubscription;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * 用户订阅实例 Repository（§5）。
 */
@Slf4j
@Repository
public class UserSubscriptionRepository {

    private static final RowMapper<UserSubscription> ROW_MAPPER = (rs, rowNum) -> {
        UserSubscription s = new UserSubscription()
                .setId(rs.getLong("id"))
                .setUserId(rs.getLong("user_id"))
                .setPlanCode(rs.getString("plan_code"))
                .setPaymentOrderId(rs.getLong("payment_order_id"))
                .setStatus(rs.getString("status"))
                .setCreditsGranted(rs.getInt("credits_granted"));
        Timestamp started = rs.getTimestamp("started_at");
        Timestamp ends = rs.getTimestamp("ends_at");
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        s.setStartedAt(started != null ? started.toInstant() : null);
        s.setEndsAt(ends != null ? ends.toInstant() : null);
        s.setCreatedAt(created != null ? created.toInstant() : null);
        s.setUpdatedAt(updated != null ? updated.toInstant() : null);
        return s;
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 查找用户当前有效的订阅（status=active 且未过期）。
     * 用于日调用上限判定（§2.1）。
     */
    public UserSubscription findActiveByUser(Long userId) {
        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT id, user_id, plan_code, payment_order_id, status, credits_granted,
                           started_at, ends_at, created_at, updated_at
                    FROM user_subscriptions
                    WHERE user_id = ? AND status = 'active' AND ends_at > ?
                    ORDER BY ends_at DESC
                    LIMIT 1
                    """,
                    ROW_MAPPER, userId, Timestamp.from(Instant.now()));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 查找所有 active 订阅（用于过期定时任务）。
     */
    public java.util.List<UserSubscription> findAllActive() {
        return jdbcTemplate.query(
                """
                SELECT id, user_id, plan_code, payment_order_id, status, credits_granted,
                       started_at, ends_at, created_at, updated_at
                FROM user_subscriptions
                WHERE status = 'active'
                """,
                ROW_MAPPER);
    }

    /**
     * 标记订阅为已过期。
     */
    public void markExpired(Long subId) {
        jdbcTemplate.update(
                "UPDATE user_subscriptions SET status = 'expired', updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), subId);
    }

    public UserSubscription insert(UserSubscription sub) {
        Instant now = Instant.now();
        String sql = """
                INSERT INTO user_subscriptions (user_id, plan_code, payment_order_id, status,
                                                credits_granted, started_at, ends_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setLong(1, sub.getUserId());
            ps.setString(2, sub.getPlanCode());
            if (sub.getPaymentOrderId() != null) {
                ps.setLong(3, sub.getPaymentOrderId());
            } else {
                ps.setNull(3, java.sql.Types.BIGINT);
            }
            ps.setString(4, sub.getStatus() != null ? sub.getStatus() : "active");
            ps.setInt(5, sub.getCreditsGranted() != null ? sub.getCreditsGranted() : 0);
            ps.setTimestamp(6, Timestamp.from(sub.getStartedAt() != null ? sub.getStartedAt() : now));
            if (sub.getEndsAt() != null) {
                ps.setTimestamp(7, Timestamp.from(sub.getEndsAt()));
            } else {
                ps.setNull(7, java.sql.Types.TIMESTAMP);
            }
            ps.setTimestamp(8, Timestamp.from(now));
            ps.setTimestamp(9, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) sub.setId(key.longValue());
        return sub;
    }
}
