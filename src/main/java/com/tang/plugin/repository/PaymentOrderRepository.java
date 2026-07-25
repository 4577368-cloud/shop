package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.user.PaymentOrder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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

@Slf4j
@Repository
public class PaymentOrderRepository {

    private static final RowMapper<PaymentOrder> ROW_MAPPER = (rs, rowNum) -> {
        PaymentOrder o = new PaymentOrder()
                .setId(rs.getLong("id"))
                .setUserId(rs.getLong("user_id"))
                .setPaypalOrderId(rs.getString("paypal_order_id"))
                .setPurpose(rs.getString("purpose"))
                .setRefId(rs.getString("ref_id"))
                .setAmountUsdCents(rs.getLong("amount_usd_cents"))
                .setAmountCnyCents(rs.getObject("amount_cny_cents", Long.class))
                .setStatus(rs.getString("status"))
                .setPaypalCaptureId(rs.getString("paypal_capture_id"))
                .setFailureReason(rs.getString("failure_reason"));
        Timestamp created = rs.getTimestamp("created_at");
        o.setCreatedAt(created != null ? created.toInstant() : null);
        Timestamp updated = rs.getTimestamp("updated_at");
        o.setUpdatedAt(updated != null ? updated.toInstant() : null);
        Timestamp captured = rs.getTimestamp("captured_at");
        o.setCapturedAt(captured != null ? captured.toInstant() : null);
        return o;
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    public PaymentOrder insert(PaymentOrder order) {
        String sql = """
                INSERT INTO payment_orders (user_id, paypal_order_id, purpose, ref_id, amount_usd_cents,
                                            amount_cny_cents, status, paypal_capture_id, failure_reason,
                                            created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        Instant now = Instant.now();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setLong(1, order.getUserId());
            ps.setString(2, order.getPaypalOrderId());
            ps.setString(3, order.getPurpose());
            ps.setString(4, order.getRefId());
            ps.setLong(5, order.getAmountUsdCents());
            ps.setObject(6, order.getAmountCnyCents());
            ps.setString(7, order.getStatus() != null ? order.getStatus() : "created");
            ps.setString(8, order.getPaypalCaptureId());
            ps.setString(9, order.getFailureReason());
            ps.setTimestamp(10, Timestamp.from(now));
            ps.setTimestamp(11, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) order.setId(key.longValue());
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        return order;
    }

    public Optional<PaymentOrder> findByPaypalOrderId(String paypalOrderId) {
        if (paypalOrderId == null || paypalOrderId.isBlank()) return Optional.empty();
        try {
            PaymentOrder o = jdbcTemplate.queryForObject(
                    """
                    SELECT id, user_id, paypal_order_id, purpose, ref_id, amount_usd_cents, amount_cny_cents,
                           status, paypal_capture_id, failure_reason, created_at, updated_at, captured_at
                    FROM payment_orders
                    WHERE paypal_order_id = ?
                    """,
                    ROW_MAPPER,
                    paypalOrderId);
            return Optional.ofNullable(o);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<PaymentOrder> findById(Long id) {
        if (id == null) return Optional.empty();
        try {
            PaymentOrder o = jdbcTemplate.queryForObject(
                    """
                    SELECT id, user_id, paypal_order_id, purpose, ref_id, amount_usd_cents, amount_cny_cents,
                           status, paypal_capture_id, failure_reason, created_at, updated_at, captured_at
                    FROM payment_orders
                    WHERE id = ?
                    """,
                    ROW_MAPPER,
                    id);
            return Optional.ofNullable(o);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 按 PayPal capture ID 查订单（webhook 幂等查询用）。
     * capture ID 仅在 capture 成功后才写入，所以仅 captured/capturing 状态的订单可能命中。
     */
    public Optional<PaymentOrder> findByCaptureId(String captureId) {
        if (captureId == null || captureId.isBlank()) return Optional.empty();
        try {
            PaymentOrder o = jdbcTemplate.queryForObject(
                    """
                    SELECT id, user_id, paypal_order_id, purpose, ref_id, amount_usd_cents, amount_cny_cents,
                           status, paypal_capture_id, failure_reason, created_at, updated_at, captured_at
                    FROM payment_orders
                    WHERE paypal_capture_id = ?
                    """,
                    ROW_MAPPER,
                    captureId);
            return Optional.ofNullable(o);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 标记为已批准（用户在 PayPal 弹窗完成批准）。 */
    public int markApproved(String paypalOrderId) {
        return jdbcTemplate.update(
                """
                UPDATE payment_orders
                SET status = 'approved', updated_at = ?
                WHERE paypal_order_id = ? AND status = 'created'
                """,
                Timestamp.from(Instant.now()), paypalOrderId);
    }

    /**
     * 原子地把状态从 created/approved 推进到 capturing（乐观锁）。
     * 用于 capturePayPalOrder 入口，串行化并发 capture 请求：
     * 第一个请求返回 1 继续走 capture；并发请求返回 0 直接走幂等查询。
     *
     * @return 1=获得 capture 权，0=已有其他请求在 capture 或已 captured
     */
    public int tryStartCapture(String paypalOrderId) {
        return jdbcTemplate.update(
                """
                UPDATE payment_orders
                SET status = 'capturing', updated_at = ?
                WHERE paypal_order_id = ? AND status IN ('created', 'approved')
                """,
                Timestamp.from(Instant.now()), paypalOrderId);
    }

    /**
     * 从 capturing 回退到 approved（capture 失败时）。
     * 不回退到 created 是因为 created→approved 不可逆（用户已批准过）。
     */
    public int revertCapturingToApproved(String paypalOrderId) {
        return jdbcTemplate.update(
                """
                UPDATE payment_orders
                SET status = 'approved', updated_at = ?
                WHERE paypal_order_id = ? AND status = 'capturing'
                """,
                Timestamp.from(Instant.now()), paypalOrderId);
    }

    /**
     * 标记为已捕获（capture 成功）。
     */
    public int markCaptured(String paypalOrderId, String captureId, Long amountCnyCents) {
        return jdbcTemplate.update(
                """
                UPDATE payment_orders
                SET status = 'captured',
                    paypal_capture_id = ?,
                    amount_cny_cents = COALESCE(?, amount_cny_cents),
                    captured_at = ?,
                    updated_at = ?
                WHERE paypal_order_id = ? AND status IN ('created', 'approved', 'capturing')
                """,
                captureId,
                amountCnyCents,
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                paypalOrderId);
    }

    /** 标记为失败。 */
    public int markFailed(String paypalOrderId, String reason) {
        return jdbcTemplate.update(
                """
                UPDATE payment_orders
                SET status = 'failed', failure_reason = ?, updated_at = ?
                WHERE paypal_order_id = ? AND status != 'captured'
                """,
                truncate(reason, 255),
                Timestamp.from(Instant.now()),
                paypalOrderId);
    }

    /** 列出用户最近的支付订单（分页）。 */
    public List<PaymentOrder> listByUser(Long userId, int limit, int offset) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(offset, 0);
        return jdbcTemplate.query(
                """
                SELECT id, user_id, paypal_order_id, purpose, ref_id, amount_usd_cents, amount_cny_cents,
                       status, paypal_capture_id, failure_reason, created_at, updated_at, captured_at
                FROM payment_orders
                WHERE user_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """,
                ROW_MAPPER,
                userId, safeLimit, safeOffset);
    }

    /** 列出用户支付订单（按 status 过滤，分页）。status 为 null 时不过滤。 */
    public List<PaymentOrder> listByUserAndStatus(Long userId, String status, int limit, int offset) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(offset, 0);
        if (StringUtils.isBlank(status)) {
            return listByUser(userId, safeLimit, safeOffset);
        }
        return jdbcTemplate.query(
                """
                SELECT id, user_id, paypal_order_id, purpose, ref_id, amount_usd_cents, amount_cny_cents,
                       status, paypal_capture_id, failure_reason, created_at, updated_at, captured_at
                FROM payment_orders
                WHERE user_id = ? AND status = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """,
                ROW_MAPPER,
                userId, status, safeLimit, safeOffset);
    }

    /** 用户支付订单总数（分页 total 用）。status 为 null 时不过滤。 */
    public int countByUser(Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_orders WHERE user_id = ?",
                Integer.class, userId);
        return count != null ? count : 0;
    }

    public int countByUserAndStatus(Long userId, String status) {
        if (StringUtils.isBlank(status)) {
            return countByUser(userId);
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_orders WHERE user_id = ? AND status = ?",
                Integer.class, userId, status);
        return count != null ? count : 0;
    }

    /** 按 id + userId 查订单（安全：防止用户越权查询他人订单）。 */
    public Optional<PaymentOrder> findByIdAndUserId(Long id, Long userId) {
        if (id == null || userId == null) return Optional.empty();
        try {
            PaymentOrder o = jdbcTemplate.queryForObject(
                    """
                    SELECT id, user_id, paypal_order_id, purpose, ref_id, amount_usd_cents, amount_cny_cents,
                           status, paypal_capture_id, failure_reason, created_at, updated_at, captured_at
                    FROM payment_orders
                    WHERE id = ? AND user_id = ?
                    """,
                    ROW_MAPPER,
                    id, userId);
            return Optional.ofNullable(o);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 列出「卡在 capturing 状态」的孤儿订单（3h 未捕获）。
     * 由 OrphanOrderCleanupService 定时调用，自愈未完成的 capture。
     *
     * @param staleBefore updated_at 早于此时间的 capturing 订单视为孤儿
     * @param limit       单次扫描上限（避免一次扫太多拖垮 PayPal API 配额）
     */
    public List<PaymentOrder> listStaleCapturingOrders(Instant staleBefore, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return jdbcTemplate.query(
                """
                SELECT id, user_id, paypal_order_id, purpose, ref_id, amount_usd_cents, amount_cny_cents,
                       status, paypal_capture_id, failure_reason, created_at, updated_at, captured_at
                FROM payment_orders
                WHERE status = 'capturing' AND updated_at < ?
                ORDER BY updated_at ASC
                LIMIT ?
                """,
                ROW_MAPPER,
                Timestamp.from(staleBefore), safeLimit);
    }

    /** 列出「created/approved 状态且超过指定时长未推进」的孤儿订单（用户中途关闭弹窗）。 */
    public List<PaymentOrder> listStalePendingOrders(Instant staleBefore, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return jdbcTemplate.query(
                """
                SELECT id, user_id, paypal_order_id, purpose, ref_id, amount_usd_cents, amount_cny_cents,
                       status, paypal_capture_id, failure_reason, created_at, updated_at, captured_at
                FROM payment_orders
                WHERE status IN ('created', 'approved') AND updated_at < ?
                ORDER BY updated_at ASC
                LIMIT ?
                """,
                ROW_MAPPER,
                Timestamp.from(staleBefore), safeLimit);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
