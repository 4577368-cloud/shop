package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.user.CreditLot;
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
import java.util.List;

@Slf4j
@Repository
public class CreditLotRepository {

    private static final RowMapper<CreditLot> ROW_MAPPER = (rs, rowNum) -> {
        CreditLot lot = new CreditLot()
                .setId(rs.getLong("id"))
                .setUserId(rs.getLong("user_id"))
                .setSourceType(rs.getString("source_type"))
                .setAmountGranted(rs.getInt("amount_granted"))
                .setAmountConsumed(rs.getInt("amount_consumed"))
                .setAmountExpired(rs.getInt("amount_expired"));
        // source_id 可能为 null
        java.math.BigDecimal sid = rs.getBigDecimal("source_id");
        lot.setSourceId(sid != null ? sid.longValue() : null);
        Timestamp expires = rs.getTimestamp("expires_at");
        lot.setExpiresAt(expires != null ? expires.toInstant() : null);
        Timestamp created = rs.getTimestamp("created_at");
        lot.setCreatedAt(created != null ? created.toInstant() : null);
        return lot;
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    public CreditLot insert(CreditLot lot) {
        String sql = """
                INSERT INTO credit_lots (user_id, source_type, source_id, amount_granted,
                                         amount_consumed, amount_expired, expires_at, created_at)
                VALUES (?, ?, ?, ?, 0, 0, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        Instant now = lot.getCreatedAt() != null ? lot.getCreatedAt() : Instant.now();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setLong(1, lot.getUserId());
            ps.setString(2, lot.getSourceType());
            if (lot.getSourceId() != null) {
                ps.setLong(3, lot.getSourceId());
            } else {
                ps.setNull(3, java.sql.Types.BIGINT);
            }
            ps.setInt(4, lot.getAmountGranted());
            if (lot.getExpiresAt() != null) {
                ps.setTimestamp(5, Timestamp.from(lot.getExpiresAt()));
            } else {
                ps.setNull(5, java.sql.Types.TIMESTAMP);
            }
            ps.setTimestamp(6, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) lot.setId(key.longValue());
        lot.setCreatedAt(now);
        return lot;
    }

    /**
     * 列出用户所有批次（用于「积分批次」页展示）。
     * 按 created_at DESC 排序（最近的在前）。
     */
    public List<CreditLot> listByUser(Long userId, int limit, int offset) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(offset, 0);
        return jdbcTemplate.query(
                """
                SELECT id, user_id, source_type, source_id, amount_granted, amount_consumed,
                       amount_expired, expires_at, created_at
                FROM credit_lots
                WHERE user_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """,
                ROW_MAPPER,
                userId, safeLimit, safeOffset);
    }

    public int countByUser(Long userId) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_lots WHERE user_id = ?",
                Integer.class, userId);
        return cnt != null ? cnt : 0;
    }

    /**
     * 取出可消耗的批次（未过期且有剩余），按 created_at ASC 顺序（FIFO 消耗）。
     *
     * <p>只读查询；扣减在事务内通过 {@link #consumeFromLot} 单条 UPDATE 完成。
     *
     * @param userId 用户 ID
     * @return 按 FIFO 排序的可消耗批次列表
     */
    public List<CreditLot> listConsumable(Long userId) {
        return jdbcTemplate.query(
                """
                SELECT id, user_id, source_type, source_id, amount_granted, amount_consumed,
                       amount_expired, expires_at, created_at
                FROM credit_lots
                WHERE user_id = ?
                  AND amount_granted - amount_consumed - amount_expired > 0
                  AND (expires_at IS NULL OR expires_at > ?)
                ORDER BY created_at ASC, id ASC
                """,
                ROW_MAPPER,
                userId, Timestamp.from(Instant.now()));
    }

    /**
     * 从指定批次扣减 n 个积分（FIFO 单步）。返回实际扣减数（≤ n）。
     *
     * <p>事务内调用。先 SELECT 当前剩余，扣减 min(剩余, n)，返回实际扣减数。
     * 当批次剩余不足时扣完所有剩余，调用方继续从下一批次扣。
     *
     * @return 实际扣减数（0=批次已无剩余）
     */
    public int consumeFromLot(Long lotId, int n) {
        if (n <= 0) return 0;
        // 1) 查当前剩余（调用方已在事务内，行级锁由后续 UPDATE 保证）
        Integer remaining = jdbcTemplate.queryForObject(
                """
                SELECT amount_granted - amount_consumed - amount_expired
                FROM credit_lots
                WHERE id = ?
                """,
                Integer.class, lotId);
        if (remaining == null || remaining <= 0) return 0;

        int toConsume = Math.min(remaining, n);
        int updated = jdbcTemplate.update(
                """
                UPDATE credit_lots
                SET amount_consumed = amount_consumed + ?
                WHERE id = ?
                  AND amount_granted - amount_consumed - amount_expired >= ?
                """,
                toConsume, lotId, toConsume);
        return updated == 0 ? 0 : toConsume;
    }

    /**
     * 批量过期：把所有已过期但未标记 expired 的批次余额置为 expired。
     * 由定时任务调用（P7 阶段）。返回受影响行数。
     */
    public int expireOverdueLots(Long userId) {
        return jdbcTemplate.update(
                """
                UPDATE credit_lots
                SET amount_expired = amount_granted - amount_consumed,
                    amount_consumed = amount_consumed
                WHERE user_id = ?
                  AND expires_at IS NOT NULL
                  AND expires_at <= ?
                  AND amount_expired < amount_granted - amount_consumed
                """,
                userId, Timestamp.from(Instant.now()));
    }
}
