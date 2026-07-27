package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.user.PaymentCreditGrant;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 支付订单→积分发放幂等表 Repository（B5）。
 *
 * <p>payment_order_id 为主键：并发捕获（前端重试 + webhook 自愈）只会有一条成功插入，
 * 其余因 PK 冲突被 {@link #insert} 吞掉并返回 false，保证积分只发放一次。
 */
@Slf4j
@Repository
public class PaymentCreditGrantRepository {

    private static final RowMapper<PaymentCreditGrant> ROW_MAPPER = (rs, rowNum) -> {
        PaymentCreditGrant g = new PaymentCreditGrant()
                .setPaymentOrderId(rs.getLong("payment_order_id"))
                .setUserId(rs.getLong("user_id"))
                .setKind(rs.getString("kind"))
                .setCode(rs.getString("code"))
                .setGrantedCredits(rs.getInt("granted_credits"))
                .setBalanceAfter(rs.getInt("balance_after"));
        Timestamp ts = rs.getTimestamp("created_at");
        g.setCreatedAt(ts != null ? ts.toInstant() : null);
        return g;
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 按支付订单查已发放记录；未发放返回 null。 */
    public PaymentCreditGrant findByPaymentOrderId(Long paymentOrderId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT payment_order_id, user_id, kind, code, granted_credits, balance_after, created_at "
                            + "FROM payment_credit_grants WHERE payment_order_id = ?",
                    ROW_MAPPER, paymentOrderId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 写入发放记录。
     *
     * @return true=写入成功；false=PK 冲突（该支付订单已发放过，由调用方走幂等返回路径）。
     */
    public boolean insert(Long paymentOrderId, Long userId, String kind, String code,
                          int grantedCredits, int balanceAfter) {
        try {
            int n = jdbcTemplate.update(
                    "INSERT INTO payment_credit_grants "
                            + "(payment_order_id, user_id, kind, code, granted_credits, balance_after, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    paymentOrderId, userId, kind, code, grantedCredits, balanceAfter,
                    Timestamp.from(Instant.now()));
            return n > 0;
        } catch (DuplicateKeyException e) {
            // 并发捕获：另一条已先写入，本事务会整体回滚（调用方 setRollbackOnly）
            return false;
        }
    }
}
