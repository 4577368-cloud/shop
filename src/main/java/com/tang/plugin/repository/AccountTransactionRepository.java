package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.user.AccountTransaction;
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
public class AccountTransactionRepository {

    private static final RowMapper<AccountTransaction> ROW_MAPPER = (rs, rowNum) -> {
        AccountTransaction t = new AccountTransaction()
                .setId(rs.getLong("id"))
                .setUserId(rs.getLong("user_id"))
                .setType(rs.getString("type"))
                .setAmountCny(rs.getLong("amount_cny"))
                .setBalanceBefore(rs.getLong("balance_before"))
                .setBalanceAfter(rs.getLong("balance_after"))
                .setRefType(rs.getString("ref_type"))
                .setRefId(rs.getString("ref_id"))
                .setRemark(rs.getString("remark"));
        Timestamp created = rs.getTimestamp("created_at");
        t.setCreatedAt(created != null ? created.toInstant() : null);
        return t;
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    public AccountTransaction insert(AccountTransaction txn) {
        String sql = """
                INSERT INTO account_transactions (user_id, type, amount_cny, balance_before, balance_after,
                                                  ref_type, ref_id, remark, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        Instant now = txn.getCreatedAt() != null ? txn.getCreatedAt() : Instant.now();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setLong(1, txn.getUserId());
            ps.setString(2, txn.getType());
            ps.setLong(3, txn.getAmountCny());
            ps.setLong(4, txn.getBalanceBefore());
            ps.setLong(5, txn.getBalanceAfter());
            ps.setString(6, txn.getRefType());
            ps.setString(7, txn.getRefId());
            ps.setString(8, txn.getRemark());
            ps.setTimestamp(9, Timestamp.from(now));
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
     * 分页查询用户流水。按 created_at DESC 排序。
     *
     * @param userId 用户 ID
     * @param type   可选类型过滤（recharge/consume/refund/adjust），null 表示全部
     * @param limit  每页条数（最大 100）
     * @param offset 偏移量
     */
    public List<AccountTransaction> listByUser(Long userId, String type, int limit, int offset) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(offset, 0);

        if (type == null || type.isBlank()) {
            return jdbcTemplate.query(
                    """
                    SELECT id, user_id, type, amount_cny, balance_before, balance_after,
                           ref_type, ref_id, remark, created_at
                    FROM account_transactions
                    WHERE user_id = ?
                    ORDER BY created_at DESC, id DESC
                    LIMIT ? OFFSET ?
                    """,
                    ROW_MAPPER,
                    userId, safeLimit, safeOffset);
        }
        return jdbcTemplate.query(
                """
                SELECT id, user_id, type, amount_cny, balance_before, balance_after,
                       ref_type, ref_id, remark, created_at
                FROM account_transactions
                WHERE user_id = ? AND type = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """,
                ROW_MAPPER,
                userId, type, safeLimit, safeOffset);
    }

    /** 流水总数（用于分页）。type 为 null 时统计全部。 */
    public int countByUser(Long userId, String type) {
        if (type == null || type.isBlank()) {
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account_transactions WHERE user_id = ?",
                    Integer.class, userId);
            return cnt != null ? cnt : 0;
        }
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_transactions WHERE user_id = ? AND type = ?",
                Integer.class, userId, type);
        return cnt != null ? cnt : 0;
    }
}
