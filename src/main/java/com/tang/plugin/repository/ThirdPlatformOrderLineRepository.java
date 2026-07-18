package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.order.ThirdPlatformOrderLine;
import com.tang.plugin.enums.order.OrderLineBindingStatus;
import com.tang.plugin.enums.order.OrderLineHandlingStatus;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC repository for {@code third_platform_order_line}. Upsert by (shop_name, line_id);
 * per-order soft delete keyed by (shop_name, outer_order_id); queries only return del_flag = 0 rows.
 */
@Slf4j
@Repository
public class ThirdPlatformOrderLineRepository {

    private static final RowMapper<ThirdPlatformOrderLine> ROW_MAPPER = (rs, rowNum) -> new ThirdPlatformOrderLine()
            .setId(rs.getLong("id"))
            .setShopName(rs.getString("shop_name"))
            .setShopType(rs.getString("shop_type"))
            .setOuterOrderId(rs.getString("outer_order_id"))
            .setLineId(rs.getString("line_id"))
            .setOuterVariantId(rs.getString("outer_variant_id"))
            .setSku(rs.getString("sku"))
            .setTitle(rs.getString("title"))
            .setQuantity((Integer) rs.getObject("quantity"))
            .setPrice(rs.getBigDecimal("price"))
            .setTangbuyProductId(rs.getString("tangbuy_product_id"))
            .setTangbuySkuId(rs.getString("tangbuy_sku_id"))
            .setBindingStatus(OrderLineBindingStatus.valueOf(rs.getString("binding_status")))
            .setHandlingStatus(toHandlingStatus(rs.getString("handling_status")))
            .setHandlingNote(rs.getString("handling_note"))
            .setHandledAt(toInstant(rs.getTimestamp("handled_at")))
            .setDraftOrderId((Long) rs.getObject("draft_order_id"))
            .setDelFlag(rs.getInt("del_flag"))
            .setCreatedAt(toInstant(rs.getTimestamp("created_at")))
            .setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));

    private static final String COLUMNS = """
            id, shop_name, shop_type, outer_order_id, line_id, outer_variant_id, sku, title, quantity,
            price, tangbuy_product_id, tangbuy_sku_id, binding_status, handling_status, handling_note,
            handled_at, draft_order_id, del_flag, created_at, updated_at
            """;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * Soft-delete all current lines of an order so a re-sync reactivates only present ones.
     * Scope fixed to (shop_name, outer_order_id) — never keyed by draft_order_id.
     */
    public int softDeleteByOrder(String shopName, String outerOrderId) {
        if (StringUtils.isAnyBlank(shopName, outerOrderId)) {
            return 0;
        }
        int rows = jdbcTemplate.update(
                "UPDATE third_platform_order_line SET del_flag = 1, updated_at = ? "
                        + "WHERE shop_name = ? AND outer_order_id = ? AND del_flag = 0",
                Timestamp.from(Instant.now()), shopName, outerOrderId);
        log.info("Order lines soft-deleted shopName={} outerOrderId={} rows={}", shopName, outerOrderId, rows);
        return rows;
    }

    /**
     * Upsert line keyed by (shop_name, line_id); reactivates del_flag = 0.
     */
    public void upsert(ThirdPlatformOrderLine line) {
        Instant now = Instant.now();
        Long id = findId(line.getShopName(), line.getLineId());
        String bindingStatus = line.getBindingStatus() == null
                ? OrderLineBindingStatus.UNBOUND.name()
                : line.getBindingStatus().name();
        if (id != null) {
            jdbcTemplate.update(
                    """
                    UPDATE third_platform_order_line
                    SET shop_type = ?, outer_order_id = ?, outer_variant_id = ?, sku = ?, title = ?,
                        quantity = ?, price = ?, tangbuy_product_id = ?, tangbuy_sku_id = ?,
                        binding_status = ?, draft_order_id = ?, del_flag = 0, updated_at = ?
                    WHERE id = ?
                    """,
                    line.getShopType(),
                    line.getOuterOrderId(),
                    line.getOuterVariantId(),
                    line.getSku(),
                    line.getTitle(),
                    line.getQuantity(),
                    line.getPrice(),
                    line.getTangbuyProductId(),
                    line.getTangbuySkuId(),
                    bindingStatus,
                    line.getDraftOrderId(),
                    Timestamp.from(now),
                    id);
            line.setId(id);
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO third_platform_order_line
                (shop_name, shop_type, outer_order_id, line_id, outer_variant_id, sku, title, quantity, price,
                 tangbuy_product_id, tangbuy_sku_id, binding_status, draft_order_id, del_flag, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """,
                line.getShopName(),
                line.getShopType(),
                line.getOuterOrderId(),
                line.getLineId(),
                line.getOuterVariantId(),
                line.getSku(),
                line.getTitle(),
                line.getQuantity(),
                line.getPrice(),
                line.getTangbuyProductId(),
                line.getTangbuySkuId(),
                bindingStatus,
                line.getDraftOrderId(),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    public List<ThirdPlatformOrderLine> listByOrder(String shopName, String outerOrderId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM third_platform_order_line "
                        + "WHERE shop_name = ? AND outer_order_id = ? AND del_flag = 0 ORDER BY id",
                ROW_MAPPER, shopName, outerOrderId);
    }

    public List<ThirdPlatformOrderLine> listByStatus(String shopName, OrderLineBindingStatus status) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM third_platform_order_line "
                        + "WHERE shop_name = ? AND binding_status = ? AND del_flag = 0 ORDER BY id",
                ROW_MAPPER, shopName, status.name());
    }

    public int countByOrderAndStatus(String shopName, String outerOrderId, OrderLineBindingStatus status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM third_platform_order_line "
                        + "WHERE shop_name = ? AND outer_order_id = ? AND binding_status = ? AND del_flag = 0",
                Integer.class, shopName, outerOrderId, status.name());
        return count == null ? 0 : count;
    }

    /**
     * Backfill a single UNBOUND line to BOUND with binding target + RESOLVED handling.
     * Scoped to del_flag = 0 AND binding_status = UNBOUND; returns affected rows (0 = not eligible).
     */
    public int backfillByLine(String shopName, String lineId, String tangbuyProductId, String tangbuySkuId) {
        return jdbcTemplate.update(
                """
                UPDATE third_platform_order_line
                SET binding_status = ?, tangbuy_product_id = ?, tangbuy_sku_id = ?,
                    handling_status = ?, handled_at = ?, updated_at = ?
                WHERE shop_name = ? AND line_id = ? AND del_flag = 0 AND binding_status = ?
                """,
                OrderLineBindingStatus.BOUND.name(),
                tangbuyProductId,
                tangbuySkuId,
                OrderLineHandlingStatus.RESOLVED.name(),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                shopName,
                lineId,
                OrderLineBindingStatus.UNBOUND.name());
    }

    /**
     * Backfill all UNBOUND lines of a variant to BOUND with binding target + RESOLVED handling.
     * Scoped to del_flag = 0 AND binding_status = UNBOUND; returns affected rows.
     */
    public int backfillByVariant(String shopName, String variantGid, String tangbuyProductId, String tangbuySkuId) {
        return jdbcTemplate.update(
                """
                UPDATE third_platform_order_line
                SET binding_status = ?, tangbuy_product_id = ?, tangbuy_sku_id = ?,
                    handling_status = ?, handled_at = ?, updated_at = ?
                WHERE shop_name = ? AND outer_variant_id = ? AND del_flag = 0 AND binding_status = ?
                """,
                OrderLineBindingStatus.BOUND.name(),
                tangbuyProductId,
                tangbuySkuId,
                OrderLineHandlingStatus.RESOLVED.name(),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                shopName,
                variantGid,
                OrderLineBindingStatus.UNBOUND.name());
    }

    public int countByVariantAndBindingStatus(String shopName, String variantGid, OrderLineBindingStatus status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM third_platform_order_line "
                        + "WHERE shop_name = ? AND outer_variant_id = ? AND binding_status = ? AND del_flag = 0",
                Integer.class, shopName, variantGid, status.name());
        return count == null ? 0 : count;
    }

    public Optional<ThirdPlatformOrderLine> findByLineId(String shopName, String lineId) {
        if (StringUtils.isAnyBlank(shopName, lineId)) {
            return Optional.empty();
        }
        try {
            ThirdPlatformOrderLine line = jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM third_platform_order_line WHERE shop_name = ? AND line_id = ?",
                    ROW_MAPPER, shopName, lineId);
            return Optional.ofNullable(line);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Update handling columns only, scoped to active UNBOUND lines. Never touches business/binding
     * columns. Returns affected rows (0 means the row is not an active UNBOUND line).
     */
    public int updateHandling(String shopName, String lineId, OrderLineHandlingStatus status,
                              String note, Instant handledAt) {
        return jdbcTemplate.update(
                """
                UPDATE third_platform_order_line
                SET handling_status = ?, handling_note = ?, handled_at = ?, updated_at = ?
                WHERE shop_name = ? AND line_id = ? AND del_flag = 0 AND binding_status = ?
                """,
                status.name(),
                note,
                handledAt == null ? null : Timestamp.from(handledAt),
                Timestamp.from(Instant.now()),
                shopName,
                lineId,
                OrderLineBindingStatus.UNBOUND.name());
    }

    /**
     * List active UNBOUND lines with optional handling-status / order filters.
     * PENDING filter matches NULL handling_status too (null is interpreted as PENDING).
     */
    public List<ThirdPlatformOrderLine> listUnbound(String shopName, OrderLineHandlingStatus handlingStatus,
                                                    String outerOrderId) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS
                + " FROM third_platform_order_line WHERE shop_name = ? AND del_flag = 0 AND binding_status = ?");
        List<Object> args = new ArrayList<>();
        args.add(shopName);
        args.add(OrderLineBindingStatus.UNBOUND.name());
        appendHandlingPredicate(sql, args, handlingStatus);
        if (StringUtils.isNotBlank(outerOrderId)) {
            sql.append(" AND outer_order_id = ?");
            args.add(outerOrderId);
        }
        sql.append(" ORDER BY id");
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    /**
     * Count active UNBOUND lines by handling status (null treated as PENDING).
     */
    public int countUnboundByHandling(String shopName, OrderLineHandlingStatus handlingStatus) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(1) FROM third_platform_order_line "
                + "WHERE shop_name = ? AND del_flag = 0 AND binding_status = ?");
        List<Object> args = new ArrayList<>();
        args.add(shopName);
        args.add(OrderLineBindingStatus.UNBOUND.name());
        appendHandlingPredicate(sql, args, handlingStatus);
        Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, args.toArray());
        return count == null ? 0 : count;
    }

    private void appendHandlingPredicate(StringBuilder sql, List<Object> args, OrderLineHandlingStatus handlingStatus) {
        if (handlingStatus == null) {
            return;
        }
        if (handlingStatus == OrderLineHandlingStatus.PENDING) {
            sql.append(" AND (handling_status IS NULL OR handling_status = ?)");
            args.add(OrderLineHandlingStatus.PENDING.name());
        } else {
            sql.append(" AND handling_status = ?");
            args.add(handlingStatus.name());
        }
    }

    private Long findId(String shopName, String lineId) {
        if (StringUtils.isAnyBlank(shopName, lineId)) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM third_platform_order_line WHERE shop_name = ? AND line_id = ?",
                    Long.class, shopName, lineId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static OrderLineHandlingStatus toHandlingStatus(String value) {
        return StringUtils.isBlank(value) ? null : OrderLineHandlingStatus.valueOf(value);
    }
}
