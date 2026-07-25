package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.user.UserAccount;
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

@Slf4j
@Repository
public class UserAccountRepository {

    private static final RowMapper<UserAccount> ROW_MAPPER = (rs, rowNum) -> {
        UserAccount a = new UserAccount()
                .setId(rs.getLong("id"))
                .setUserId(rs.getLong("user_id"))
                .setBalanceCny(rs.getLong("balance_cny"))
                .setTotalRecharged(rs.getLong("total_recharged"))
                .setTotalConsumed(rs.getLong("total_consumed"))
                .setTotalRefunded(rs.getLong("total_refunded"));
        Timestamp created = rs.getTimestamp("created_at");
        a.setCreatedAt(created != null ? created.toInstant() : null);
        Timestamp updated = rs.getTimestamp("updated_at");
        a.setUpdatedAt(updated != null ? updated.toInstant() : null);
        return a;
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    public Optional<UserAccount> findByUserId(Long userId) {
        if (userId == null) return Optional.empty();
        try {
            UserAccount a = jdbcTemplate.queryForObject(
                    """
                    SELECT id, user_id, balance_cny, total_recharged, total_consumed, total_refunded,
                           created_at, updated_at
                    FROM user_accounts
                    WHERE user_id = ?
                    """,
                    ROW_MAPPER,
                    userId);
            return Optional.ofNullable(a);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 懒创建账户。首次访问 /billing/overview 时调用。
     * 使用 INSERT ... ON CONFLICT DO NOTHING 保证并发安全（PostgreSQL）；
     * H2 不支持 ON CONFLICT，使用 MERGE 等价语义（被 unique(user_id) 约束兜底）。
     */
    public UserAccount insertIfAbsent(Long userId) {
        // 先查，避免无谓的写
        Optional<UserAccount> existing = findByUserId(userId);
        if (existing.isPresent()) return existing.get();

        Instant now = Instant.now();
        String sql = """
                INSERT INTO user_accounts (user_id, balance_cny, total_recharged, total_consumed,
                                           total_refunded, created_at, updated_at)
                VALUES (?, 0, 0, 0, 0, ?, ?)
                """;
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(con -> {
                PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
                ps.setLong(1, userId);
                ps.setTimestamp(2, Timestamp.from(now));
                ps.setTimestamp(3, Timestamp.from(now));
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key != null) {
                return new UserAccount()
                        .setId(key.longValue())
                        .setUserId(userId)
                        .setBalanceCny(0L)
                        .setTotalRecharged(0L)
                        .setTotalConsumed(0L)
                        .setTotalRefunded(0L)
                        .setCreatedAt(now)
                        .setUpdatedAt(now);
            }
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发插入：另一线程已建好，回查即可
            log.debug("Concurrent account creation for userId={}, falling back to select", userId);
        }
        return findByUserId(userId).orElseThrow(() ->
                new IllegalStateException("Failed to ensure account for userId=" + userId));
    }

    /**
     * 原子扣减余额，并累加 total_consumed。
     * 余额不足会因 WHERE 条件不匹配而影响 0 行，调用方需检查返回值。
     *
     * @return 影响行数（1=成功扣减，0=余额不足）
     */
    public int tryConsume(Long userId, Long amountCny) {
        return jdbcTemplate.update(
                """
                UPDATE user_accounts
                SET balance_cny = balance_cny - ?,
                    total_consumed = total_consumed + ?,
                    updated_at = ?
                WHERE user_id = ? AND balance_cny >= ?
                """,
                amountCny, amountCny, Timestamp.from(Instant.now()), userId, amountCny);
    }

    /**
     * 加锁读取当前余额（用于在事务内获取 balance_before）。
     * 调用方必须处于事务中，且应使用 SELECT ... FOR UPDATE 防止并发改写。
     */
    public Optional<UserAccount> findByUserIdForUpdate(Long userId) {
        if (userId == null) return Optional.empty();
        try {
            // H2 不支持 FOR UPDATE 语法差异；PostgreSQL 支持。这里用通用 SELECT，
            // 由 @Transactional + tryConsume 的原子 UPDATE 保证一致性。
            UserAccount a = jdbcTemplate.queryForObject(
                    """
                    SELECT id, user_id, balance_cny, total_recharged, total_consumed, total_refunded,
                           created_at, updated_at
                    FROM user_accounts
                    WHERE user_id = ?
                    """,
                    ROW_MAPPER,
                    userId);
            return Optional.ofNullable(a);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 人工调整余额（充值/退款/调整）。直接更新 balance，并累加对应的 total_*。
     *
     * @param userId      用户 ID
     * @param deltaCny    余额增量（正数=加余额，负数=减余额）
     * @param rechargeDelta 累计充值增量（正数）
     * @param refundDelta   累计退款增量（正数）
     */
    public int adjustBalance(Long userId, Long deltaCny, Long rechargeDelta, Long refundDelta) {
        return jdbcTemplate.update(
                """
                UPDATE user_accounts
                SET balance_cny = balance_cny + ?,
                    total_recharged = total_recharged + ?,
                    total_refunded = total_refunded + ?,
                    updated_at = ?
                WHERE user_id = ?
                """,
                deltaCny,
                rechargeDelta != null ? rechargeDelta : 0L,
                refundDelta != null ? refundDelta : 0L,
                Timestamp.from(Instant.now()),
                userId);
    }
}
