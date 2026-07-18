package com.tang.plugin.repository;

import com.tang.plugin.domain.dto.procurement.ProcurementConsumptionView;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementConsumption;
import com.tang.plugin.enums.procurement.ProcurementConsumptionStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskDeliveryStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskStatus;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC repository for {@code third_platform_procurement_consumption} (consumer ledger).
 * Idempotent by (task_id, consumer_id); queries scope to del_flag = 0. Timestamps are written once.
 */
@Slf4j
@Repository
public class ThirdPlatformProcurementConsumptionRepository {

    private static final RowMapper<ThirdPlatformProcurementConsumption> ROW_MAPPER = (rs, rowNum) ->
            new ThirdPlatformProcurementConsumption()
                    .setId(rs.getLong("id"))
                    .setShopName(rs.getString("shop_name"))
                    .setTaskId(rs.getLong("task_id"))
                    .setLineId(rs.getString("line_id"))
                    .setConsumerId(rs.getString("consumer_id"))
                    .setConsumerRef(rs.getString("consumer_ref"))
                    .setConsumptionStatus(ProcurementConsumptionStatus.valueOf(rs.getString("consumption_status")))
                    .setReceivedAt(toInstant(rs.getTimestamp("received_at")))
                    .setAcceptedAt(toInstant(rs.getTimestamp("accepted_at")))
                    .setDelFlag(rs.getInt("del_flag"))
                    .setCreatedAt(toInstant(rs.getTimestamp("created_at")))
                    .setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));

    private static final RowMapper<ProcurementConsumptionView> VIEW_ROW_MAPPER = (rs, rowNum) ->
            new ProcurementConsumptionView()
                    .setId(rs.getLong("id"))
                    .setShopName(rs.getString("shop_name"))
                    .setTaskId(rs.getLong("task_id"))
                    .setLineId(rs.getString("line_id"))
                    .setConsumerId(rs.getString("consumer_id"))
                    .setConsumerRef(rs.getString("consumer_ref"))
                    .setConsumptionStatus(ProcurementConsumptionStatus.valueOf(rs.getString("consumption_status")))
                    .setReceivedAt(toInstant(rs.getTimestamp("received_at")))
                    .setAcceptedAt(toInstant(rs.getTimestamp("accepted_at")))
                    .setTaskStatus(ProcurementTaskStatus.valueOf(rs.getString("task_status")))
                    .setDeliveryStatus(ProcurementTaskDeliveryStatus.valueOf(rs.getString("delivery_status")));

    private static final String COLUMNS = """
            id, shop_name, task_id, line_id, consumer_id, consumer_ref, consumption_status,
            received_at, accepted_at, del_flag, created_at, updated_at
            """;

    private static final String VIEW_SELECT = """
            SELECT c.id, c.shop_name, c.task_id, c.line_id, c.consumer_id, c.consumer_ref,
                   c.consumption_status, c.received_at, c.accepted_at,
                   t.task_status, t.delivery_status
            FROM third_platform_procurement_consumption c
            JOIN third_platform_procurement_task t ON t.id = c.task_id
            """;

    @Resource
    private JdbcTemplate jdbcTemplate;

    public Optional<ThirdPlatformProcurementConsumption> findByKey(Long taskId, String consumerId) {
        if (taskId == null || StringUtils.isBlank(consumerId)) {
            return Optional.empty();
        }
        try {
            ThirdPlatformProcurementConsumption row = jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM third_platform_procurement_consumption "
                            + "WHERE task_id = ? AND consumer_id = ?",
                    ROW_MAPPER, taskId, consumerId);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<ThirdPlatformProcurementConsumption> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        try {
            ThirdPlatformProcurementConsumption row = jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM third_platform_procurement_consumption WHERE id = ?",
                    ROW_MAPPER, id);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Insert a RECEIVED receipt (received_at = now). Caller guarantees absence via findByKey within
     * the same transaction; the unique key (task_id, consumer_id) is the final safety net.
     */
    public Long insertReceived(ThirdPlatformProcurementConsumption receipt) {
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO third_platform_procurement_consumption
                    (shop_name, task_id, line_id, consumer_id, consumer_ref, consumption_status,
                     received_at, del_flag, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """,
                    new String[]{"id"});
            ps.setString(1, receipt.getShopName());
            ps.setLong(2, receipt.getTaskId());
            ps.setString(3, receipt.getLineId());
            ps.setString(4, receipt.getConsumerId());
            ps.setString(5, receipt.getConsumerRef());
            ps.setString(6, ProcurementConsumptionStatus.RECEIVED.name());
            ps.setTimestamp(7, Timestamp.from(now));
            ps.setTimestamp(8, Timestamp.from(now));
            ps.setTimestamp(9, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    /**
     * Transition RECEIVED -> ACCEPTED for the given id, guarded so a re-accept is a no-op (0 rows)
     * and accepted_at is never refreshed.
     */
    public int markAccepted(Long id) {
        if (id == null) {
            return 0;
        }
        Timestamp now = Timestamp.from(Instant.now());
        return jdbcTemplate.update(
                "UPDATE third_platform_procurement_consumption "
                        + "SET consumption_status = ?, accepted_at = ?, updated_at = ? "
                        + "WHERE id = ? AND consumption_status = ?",
                ProcurementConsumptionStatus.ACCEPTED.name(), now, now, id,
                ProcurementConsumptionStatus.RECEIVED.name());
    }

    public List<ProcurementConsumptionView> listViewByTask(String shopName, Long taskId) {
        if (StringUtils.isBlank(shopName) || taskId == null) {
            return Collections.emptyList();
        }
        return jdbcTemplate.query(
                VIEW_SELECT + "WHERE c.shop_name = ? AND c.task_id = ? AND c.del_flag = 0 ORDER BY c.id ASC",
                VIEW_ROW_MAPPER, shopName, taskId);
    }

    public List<ProcurementConsumptionView> listViewByStatus(
            String shopName, ProcurementConsumptionStatus status) {
        if (StringUtils.isBlank(shopName) || status == null) {
            return Collections.emptyList();
        }
        return jdbcTemplate.query(
                VIEW_SELECT + "WHERE c.shop_name = ? AND c.consumption_status = ? AND c.del_flag = 0 "
                        + "ORDER BY c.id ASC",
                VIEW_ROW_MAPPER, shopName, status.name());
    }

    /**
     * Batch fetch receipts for many tasks (avoids N+1 during chain assembly), ordered by id ASC.
     */
    public List<ProcurementConsumptionView> listViewByTaskIds(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Collections.emptyList();
        }
        String placeholders = String.join(",", Collections.nCopies(taskIds.size(), "?"));
        return jdbcTemplate.query(
                VIEW_SELECT + "WHERE c.del_flag = 0 AND c.task_id IN (" + placeholders + ") ORDER BY c.id ASC",
                VIEW_ROW_MAPPER, taskIds.toArray());
    }

    public Map<String, Long> countGroupByConsumptionStatus(String shopName) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (StringUtils.isBlank(shopName)) {
            return counts;
        }
        jdbcTemplate.query(
                "SELECT consumption_status AS k, COUNT(*) AS c FROM third_platform_procurement_consumption "
                        + "WHERE shop_name = ? AND del_flag = 0 GROUP BY consumption_status",
                rs -> {
                    counts.put(rs.getString("k"), rs.getLong("c"));
                },
                shopName);
        return counts;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
