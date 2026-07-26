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
