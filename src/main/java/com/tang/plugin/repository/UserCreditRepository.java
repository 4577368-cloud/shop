package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.user.UserCredit;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
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
 * 用户积分账户 Repository。与 UserAccountRepository 同样的并发安全策略。
 *
 * <p>账户懒创建：首次访问 /billing/credits/balance 时由 CreditService.ensureAccount() 调用 insertIfAbsent。
 */
@Slf4j
@Repository
public class UserCreditRepository {

    private static final RowMapper<UserCredit> ROW_MAPPER = (rs, rowNum) -> {
        UserCredit c = new UserCredit()
                .setId(rs.getLong("id"))
                .setUserId(rs.getLong("user_id"))
                .setBalanceCredits(rs.getInt("balance_credits"))
                .setTotalGranted(rs.getInt("total_granted"))
                .setTotalConsumed(rs.getInt("total_consumed"))
                .setTotalExpired(rs.getInt("total_expired"));
        Timestamp created = rs.getTimestamp("created_at");
        c.setCreatedAt(created != null ? created.toInstant() : null);
        Timestamp updated = rs.getTimestamp("updated_at");
        c.setUpdatedAt(updated != null ? updated.toInstant() : null);
        return c;
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    public Optional<UserCredit> findByUserId(Long userId) {
        if (userId == null) return Optional.empty();
        try {
            UserCredit c = jdbcTemplate.queryForObject(
                    """
                    SELECT id, user_id, balance_credits, total_granted, total_consumed, total_expired,
                           created_at, updated_at
                    FROM user_credits
                    WHERE user_id = ?
                    """,
                    ROW_MAPPER,
                    userId);
            return Optional.ofNullable(c);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 懒创建账户。INSERT 失败（unique 冲突）时回查。
     */
    public UserCredit insertIfAbsent(Long userId) {
        Optional<UserCredit> existing = findByUserId(userId);
        if (existing.isPresent()) return existing.get();

        Instant now = Instant.now();
        String sql = """
                INSERT INTO user_credits (user_id, balance_credits, total_granted, total_consumed,
                                          total_expired, created_at, updated_at)
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
                return new UserCredit()
                        .setId(key.longValue())
                        .setUserId(userId)
                        .setBalanceCredits(0)
                        .setTotalGranted(0)
                        .setTotalConsumed(0)
                        .setTotalExpired(0)
                        .setCreatedAt(now)
                        .setUpdatedAt(now);
            }
        } catch (DuplicateKeyException e) {
            log.debug("Concurrent credit account creation for userId={}, falling back to select", userId);
        }
        return findByUserId(userId).orElseThrow(() ->
                new IllegalStateException("Failed to ensure credit account for userId=" + userId));
    }

    /**
     * 原子扣减积分，并累加 total_consumed。
     * 余额不足时 WHERE 不匹配，影响 0 行，调用方检查返回值。
     *
     * @return 影响行数（1=成功，0=余额不足）
     */
    public int tryConsume(Long userId, int amount) {
        return jdbcTemplate.update(
                """
                UPDATE user_credits
                SET balance_credits = balance_credits - ?,
                    total_consumed = total_consumed + ?,
                    updated_at = ?
                WHERE user_id = ? AND balance_credits >= ?
                """,
                amount, amount, Timestamp.from(Instant.now()), userId, amount);
    }

    /**
     * 加积分（发放/调整），并累加 total_granted。
     *
     * @param userId       用户 ID
     * @param deltaCredits 积分增量（正数）
     */
    public int addCredits(Long userId, int deltaCredits) {
        return jdbcTemplate.update(
                """
                UPDATE user_credits
                SET balance_credits = balance_credits + ?,
                    total_granted = total_granted + ?,
                    updated_at = ?
                WHERE user_id = ?
                """,
                deltaCredits, deltaCredits, Timestamp.from(Instant.now()), userId);
    }

    /**
     * 累加过期积分（不改变 balance，过期由独立流程处理）。
     */
    public int addExpired(Long userId, int expiredDelta) {
        return jdbcTemplate.update(
                """
                UPDATE user_credits
                SET total_expired = total_expired + ?,
                    updated_at = ?
                WHERE user_id = ?
                """,
                expiredDelta, Timestamp.from(Instant.now()), userId);
    }

    /**
     * 过期扣减：从 balance_credits 扣除过期部分，并累加 total_expired。
     * 由定时任务调用，与 {@code CreditLotRepository.expireOverdueLots} 配合使用。
     */
    public int deductExpired(Long userId, int expiredAmount) {
        return jdbcTemplate.update(
                """
                UPDATE user_credits
                SET balance_credits = balance_credits - ?,
                    total_expired = total_expired + ?,
                    updated_at = ?
                WHERE user_id = ? AND balance_credits >= ?
                """,
                expiredAmount, expiredAmount, Timestamp.from(Instant.now()), userId, expiredAmount);
    }
}
