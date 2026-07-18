package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementTask;
import com.tang.plugin.enums.procurement.ProcurementTaskDeliveryStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskStatus;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC repository for {@code third_platform_procurement_task} (outbox). Idempotent by
 * (shop_name, line_id); queries scope to del_flag = 0. No snapshot refresh on idempotent hit.
 */
@Slf4j
@Repository
public class ThirdPlatformProcurementTaskRepository {

    private static final RowMapper<ThirdPlatformProcurementTask> ROW_MAPPER = (rs, rowNum) ->
            new ThirdPlatformProcurementTask()
                    .setId(rs.getLong("id"))
                    .setShopName(rs.getString("shop_name"))
                    .setShopType(rs.getString("shop_type"))
                    .setOuterOrderId(rs.getString("outer_order_id"))
                    .setLineId(rs.getString("line_id"))
                    .setTangbuyProductId(rs.getString("tangbuy_product_id"))
                    .setTangbuySkuId(rs.getString("tangbuy_sku_id"))
                    .setQuantity((Integer) rs.getObject("quantity"))
                    .setCurrency(rs.getString("currency"))
                    .setUnitPrice(rs.getBigDecimal("unit_price"))
                    .setTaskStatus(ProcurementTaskStatus.valueOf(rs.getString("task_status")))
                    .setDeliveryStatus(parseDeliveryStatus(rs.getString("delivery_status")))
                    .setDeliveredAt(toInstant(rs.getTimestamp("delivered_at")))
                    .setDeliveryAttempts(rs.getInt("delivery_attempts"))
                    .setLastPulledAt(toInstant(rs.getTimestamp("last_pulled_at")))
                    .setDelFlag(rs.getInt("del_flag"))
                    .setCreatedAt(toInstant(rs.getTimestamp("created_at")))
                    .setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));

    private static final String COLUMNS = """
            id, shop_name, shop_type, outer_order_id, line_id, tangbuy_product_id, tangbuy_sku_id,
            quantity, currency, unit_price, task_status, delivery_status, delivered_at,
            delivery_attempts, last_pulled_at, del_flag, created_at, updated_at
            """;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * Return existing task id (any del_flag) if present; otherwise insert. On unique-key race,
     * re-read and return the existing id. Never refreshes snapshot fields on hit.
     */
    public Long saveIfAbsent(ThirdPlatformProcurementTask task) {
        Long existing = findIdByLine(task.getShopName(), task.getLineId());
        if (existing != null) {
            log.info("Procurement task idempotent hit id={} shopName={} lineId={}",
                    existing, task.getShopName(), task.getLineId());
            return existing;
        }
        try {
            Long id = insert(task);
            log.info("Procurement task created id={} shopName={} outerOrderId={} lineId={} tangbuySkuId={}",
                    id, task.getShopName(), task.getOuterOrderId(), task.getLineId(), task.getTangbuySkuId());
            return id;
        } catch (DuplicateKeyException e) {
            Long raced = findIdByLine(task.getShopName(), task.getLineId());
            log.info("Procurement task insert race resolved id={} shopName={} lineId={}",
                    raced, task.getShopName(), task.getLineId());
            return raced;
        }
    }

    public Optional<ThirdPlatformProcurementTask> findByLine(String shopName, String lineId) {
        if (StringUtils.isAnyBlank(shopName, lineId)) {
            return Optional.empty();
        }
        try {
            ThirdPlatformProcurementTask task = jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM third_platform_procurement_task "
                            + "WHERE shop_name = ? AND line_id = ? AND del_flag = 0",
                    ROW_MAPPER, shopName, lineId);
            return Optional.ofNullable(task);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<ThirdPlatformProcurementTask> listByOrder(String shopName, String outerOrderId) {
        if (StringUtils.isAnyBlank(shopName, outerOrderId)) {
            return Collections.emptyList();
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM third_platform_procurement_task "
                        + "WHERE shop_name = ? AND outer_order_id = ? AND del_flag = 0 ORDER BY id",
                ROW_MAPPER, shopName, outerOrderId);
    }

    public List<ThirdPlatformProcurementTask> listByStatus(String shopName, ProcurementTaskStatus status) {
        if (StringUtils.isBlank(shopName) || status == null) {
            return Collections.emptyList();
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM third_platform_procurement_task "
                        + "WHERE shop_name = ? AND task_status = ? AND del_flag = 0 ORDER BY id",
                ROW_MAPPER, shopName, status.name());
    }

    public int updateStatus(Long id, ProcurementTaskStatus status) {
        return jdbcTemplate.update(
                "UPDATE third_platform_procurement_task SET task_status = ?, updated_at = ? WHERE id = ?",
                status.name(), Timestamp.from(Instant.now()), id);
    }

    public Optional<ThirdPlatformProcurementTask> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        try {
            ThirdPlatformProcurementTask task = jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM third_platform_procurement_task WHERE id = ?",
                    ROW_MAPPER, id);
            return Optional.ofNullable(task);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Deliverable tasks: del_flag = 0, task_status = PENDING, delivery_status = PENDING_DELIVERY.
     * Stably ordered by id ASC and capped by {@code limit}. Read-only (no claim in P1).
     */
    public List<ThirdPlatformProcurementTask> listDeliverable(String shopName, int limit) {
        if (StringUtils.isBlank(shopName) || limit <= 0) {
            return Collections.emptyList();
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM third_platform_procurement_task "
                        + "WHERE shop_name = ? AND del_flag = 0 AND task_status = ? AND delivery_status = ? "
                        + "ORDER BY id ASC LIMIT ?",
                ROW_MAPPER, shopName, ProcurementTaskStatus.PENDING.name(),
                ProcurementTaskDeliveryStatus.PENDING_DELIVERY.name(), limit);
    }

    public List<ThirdPlatformProcurementTask> listByDeliveryStatus(
            String shopName, ProcurementTaskDeliveryStatus deliveryStatus) {
        if (StringUtils.isBlank(shopName) || deliveryStatus == null) {
            return Collections.emptyList();
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM third_platform_procurement_task "
                        + "WHERE shop_name = ? AND delivery_status = ? AND del_flag = 0 ORDER BY id ASC",
                ROW_MAPPER, shopName, deliveryStatus.name());
    }

    /**
     * Ops read: active tasks for a shop with optional task_status / delivery_status filters,
     * stably ordered by id ASC and capped by {@code limit}. Read-only.
     */
    public List<ThirdPlatformProcurementTask> listByShop(
            String shopName, ProcurementTaskStatus taskStatus,
            ProcurementTaskDeliveryStatus deliveryStatus, int limit) {
        if (StringUtils.isBlank(shopName) || limit <= 0) {
            return Collections.emptyList();
        }
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS
                + " FROM third_platform_procurement_task WHERE shop_name = ? AND del_flag = 0");
        List<Object> args = new ArrayList<>();
        args.add(shopName);
        if (taskStatus != null) {
            sql.append(" AND task_status = ?");
            args.add(taskStatus.name());
        }
        if (deliveryStatus != null) {
            sql.append(" AND delivery_status = ?");
            args.add(deliveryStatus.name());
        }
        sql.append(" ORDER BY id ASC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    public Map<String, Long> countGroupByTaskStatus(String shopName) {
        return countGroupBy(shopName, "task_status");
    }

    public Map<String, Long> countGroupByDeliveryStatus(String shopName) {
        return countGroupBy(shopName, "delivery_status");
    }

    private Map<String, Long> countGroupBy(String shopName, String column) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (StringUtils.isBlank(shopName)) {
            return counts;
        }
        jdbcTemplate.query(
                "SELECT " + column + " AS k, COUNT(*) AS c FROM third_platform_procurement_task "
                        + "WHERE shop_name = ? AND del_flag = 0 GROUP BY " + column,
                rs -> {
                    counts.put(rs.getString("k"), rs.getLong("c"));
                },
                shopName);
        return counts;
    }

    /**
     * Observational pulled marker for the ids actually pulled: delivery_attempts += 1 and
     * last_pulled_at = now. Does not change delivery_status (pull is read-only in P1).
     */
    public int markPulled(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        Timestamp now = Timestamp.from(Instant.now());
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Object[] args = new Object[ids.size() + 2];
        args[0] = now;
        args[1] = now;
        for (int i = 0; i < ids.size(); i++) {
            args[i + 2] = ids.get(i);
        }
        return jdbcTemplate.update(
                "UPDATE third_platform_procurement_task "
                        + "SET delivery_attempts = delivery_attempts + 1, last_pulled_at = ?, updated_at = ? "
                        + "WHERE id IN (" + placeholders + ")",
                args);
    }

    /**
     * Transition PENDING_DELIVERY -> DELIVERED for the given id, guarded so a re-ack of an
     * already DELIVERED row is a no-op (0 rows) and delivered_at is never refreshed.
     */
    public int markDelivered(Long id) {
        if (id == null) {
            return 0;
        }
        Timestamp now = Timestamp.from(Instant.now());
        return jdbcTemplate.update(
                "UPDATE third_platform_procurement_task "
                        + "SET delivery_status = ?, delivered_at = ?, updated_at = ? "
                        + "WHERE id = ? AND delivery_status = ?",
                ProcurementTaskDeliveryStatus.DELIVERED.name(), now, now, id,
                ProcurementTaskDeliveryStatus.PENDING_DELIVERY.name());
    }

    private Long findIdByLine(String shopName, String lineId) {
        if (StringUtils.isAnyBlank(shopName, lineId)) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM third_platform_procurement_task WHERE shop_name = ? AND line_id = ?",
                    Long.class, shopName, lineId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private Long insert(ThirdPlatformProcurementTask task) {
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO third_platform_procurement_task
                    (shop_name, shop_type, outer_order_id, line_id, tangbuy_product_id, tangbuy_sku_id,
                     quantity, currency, unit_price, task_status, del_flag, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """,
                    new String[]{"id"});
            ps.setString(1, task.getShopName());
            ps.setString(2, task.getShopType());
            ps.setString(3, task.getOuterOrderId());
            ps.setString(4, task.getLineId());
            ps.setString(5, task.getTangbuyProductId());
            ps.setString(6, task.getTangbuySkuId());
            if (task.getQuantity() == null) {
                ps.setNull(7, Types.INTEGER);
            } else {
                ps.setInt(7, task.getQuantity());
            }
            ps.setString(8, task.getCurrency());
            if (task.getUnitPrice() == null) {
                ps.setNull(9, Types.DECIMAL);
            } else {
                ps.setBigDecimal(9, task.getUnitPrice());
            }
            ps.setString(10, ProcurementTaskStatus.PENDING.name());
            ps.setTimestamp(11, Timestamp.from(now));
            ps.setTimestamp(12, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static ProcurementTaskDeliveryStatus parseDeliveryStatus(String value) {
        return StringUtils.isBlank(value)
                ? ProcurementTaskDeliveryStatus.PENDING_DELIVERY
                : ProcurementTaskDeliveryStatus.valueOf(value);
    }
}
