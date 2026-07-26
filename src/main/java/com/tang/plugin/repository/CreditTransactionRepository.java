package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.user.CreditTransaction;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.apache.commons.lang3.StringUtils;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Slf4j
@Repository
public class CreditTransactionRepository {

    private static final RowMapper<CreditTransaction> ROW_MAPPER = (rs, rowNum) -> {
        CreditTransaction t = new CreditTransaction()
                .setId(rs.getLong("id"))
                .setUserId(rs.getLong("user_id"))
                .setType(rs.getString("type"))
                .setAmount(rs.getInt("amount"))
                .setBalanceBefore(rs.getInt("balance_before"))
                .setBalanceAfter(rs.getInt("balance_after"))
                .setRefType(rs.getString("ref_type"))
                .setRefId(rs.getString("ref_id"))
                .setEndpoint(rs.getString("endpoint"))
                .setRemark(rs.getString("remark"))
                .setIdempotencyKey(rs.getString("idempotency_key"));
        Timestamp created = rs.getTimestamp("created_at");
        t.setCreatedAt(created != null ? created.toInstant() : null);
        return t;
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    public CreditTransaction insert(CreditTransaction txn) {
        String sql = """
                INSERT INTO credit_transactions (user_id, type, amount, balance_before, balance_after,
                                                 ref_type, ref_id, endpoint, remark, idempotency_key, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        Instant now = txn.getCreatedAt() != null ? txn.getCreatedAt() : Instant.now();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setLong(1, txn.getUserId());
            ps.setString(2, txn.getType());
            ps.setInt(3, txn.getAmount());
            ps.setInt(4, txn.getBalanceBefore());
            ps.setInt(5, txn.getBalanceAfter());
            ps.setString(6, txn.getRefType());
            ps.setString(7, txn.getRefId());
            ps.setString(8, txn.getEndpoint());
            ps.setString(9, txn.getRemark());
            ps.setString(10, txn.getIdempotencyKey());
            ps.setTimestamp(11, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            txn.setId(key.longValue());
        }
        txn.setCreatedAt(now);
        return txn;
    }

    /**
     * 按幂等键查询已存在的消耗记录。用于防止重复扣费。
     */
    public CreditTransaction findByIdempotencyKey(Long userId, String idempotencyKey) {
        if (StringUtils.isBlank(idempotencyKey)) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT id, user_id, type, amount, balance_before, balance_after,
                           ref_type, ref_id, endpoint, remark, idempotency_key, created_at
                    FROM credit_transactions
                    WHERE user_id = ? AND idempotency_key = ?
                    LIMIT 1
                    """,
                    ROW_MAPPER, userId, idempotencyKey);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 分页查询用户积分流水。按 created_at DESC 排序。
     *
     * @param type 可选类型过滤（grant/consume/expire/adjust）
     */
    public List<CreditTransaction> listByUser(Long userId, String type, int limit, int offset) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(offset, 0);

        if (type == null || type.isBlank()) {
            return jdbcTemplate.query(
                    """
                    SELECT id, user_id, type, amount, balance_before, balance_after,
                           ref_type, ref_id, endpoint, remark, created_at
                    FROM credit_transactions
                    WHERE user_id = ?
                    ORDER BY created_at DESC, id DESC
                    LIMIT ? OFFSET ?
                    """,
                    ROW_MAPPER,
                    userId, safeLimit, safeOffset);
        }
        return jdbcTemplate.query(
                """
                SELECT id, user_id, type, amount, balance_before, balance_after,
                       ref_type, ref_id, endpoint, remark, created_at
                FROM credit_transactions
                WHERE user_id = ? AND type = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """,
                ROW_MAPPER,
                userId, type, safeLimit, safeOffset);
    }

    public int countByUser(Long userId, String type) {
        if (type == null || type.isBlank()) {
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM credit_transactions WHERE user_id = ?",
                    Integer.class, userId);
            return cnt != null ? cnt : 0;
        }
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_transactions WHERE user_id = ? AND type = ?",
                Integer.class, userId, type);
        return cnt != null ? cnt : 0;
    }
}
