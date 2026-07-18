package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementExecution;
import com.tang.plugin.enums.procurement.ProcurementExecutionStatus;
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
import java.util.List;
import java.util.Optional;

/**
 * JDBC repository for {@code third_platform_procurement_execution} (execution stub).
 * One row per task (idempotent by task_id); queries scope to del_flag = 0. completed_at written once.
 */
@Slf4j
@Repository
public class ThirdPlatformProcurementExecutionRepository {

    private static final RowMapper<ThirdPlatformProcurementExecution> ROW_MAPPER = (rs, rowNum) ->
            new ThirdPlatformProcurementExecution()
                    .setId(rs.getLong("id"))
                    .setShopName(rs.getString("shop_name"))
                    .setTaskId(rs.getLong("task_id"))
                    .setLineId(rs.getString("line_id"))
                    .setConsumerId(rs.getString("consumer_id"))
                    .setExecutionStatus(ProcurementExecutionStatus.valueOf(rs.getString("execution_status")))
                    .setNote(rs.getString("note"))
                    .setCreatedAt(toInstant(rs.getTimestamp("created_at")))
                    .setCompletedAt(toInstant(rs.getTimestamp("completed_at")))
                    .setDelFlag(rs.getInt("del_flag"))
                    .setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));

    private static final String COLUMNS = """
            id, shop_name, task_id, line_id, consumer_id, execution_status, note,
            created_at, completed_at, del_flag, updated_at
            """;

    @Resource
    private JdbcTemplate jdbcTemplate;

    public Optional<ThirdPlatformProcurementExecution> findByTask(Long taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        try {
            ThirdPlatformProcurementExecution row = jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM third_platform_procurement_execution WHERE task_id = ?",
                    ROW_MAPPER, taskId);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<ThirdPlatformProcurementExecution> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        try {
            ThirdPlatformProcurementExecution row = jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM third_platform_procurement_execution WHERE id = ?",
                    ROW_MAPPER, id);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Insert a PENDING_EXECUTION stub. Caller guarantees absence via findByTask within the same
     * transaction; the unique key (task_id) is the final safety net.
     */
    public Long insertPending(ThirdPlatformProcurementExecution execution) {
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO third_platform_procurement_execution
                    (shop_name, task_id, line_id, consumer_id, execution_status, note,
                     created_at, del_flag, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?)
                    """,
                    new String[]{"id"});
            ps.setString(1, execution.getShopName());
            ps.setLong(2, execution.getTaskId());
            ps.setString(3, execution.getLineId());
            ps.setString(4, execution.getConsumerId());
            ps.setString(5, ProcurementExecutionStatus.PENDING_EXECUTION.name());
            ps.setString(6, execution.getNote());
            ps.setTimestamp(7, Timestamp.from(now));
            ps.setTimestamp(8, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    /**
     * Transition PENDING_EXECUTION -> COMPLETED_STUB, guarded so a re-complete is a no-op (0 rows)
     * and completed_at is never refreshed. Optional note is written only on the transition.
     */
    public int markCompleted(Long id, String note) {
        if (id == null) {
            return 0;
        }
        Timestamp now = Timestamp.from(Instant.now());
        return jdbcTemplate.update(
                "UPDATE third_platform_procurement_execution "
                        + "SET execution_status = ?, completed_at = ?, updated_at = ?, note = COALESCE(?, note) "
                        + "WHERE id = ? AND execution_status = ?",
                ProcurementExecutionStatus.COMPLETED_STUB.name(), now, now, note, id,
                ProcurementExecutionStatus.PENDING_EXECUTION.name());
    }

    /**
     * Batch fetch executions for the given task ids (del_flag = 0), ordered by id ASC.
     * Used to assemble chain views without N+1 queries. Returns empty for null/empty input.
     */
    public List<ThirdPlatformProcurementExecution> listByTaskIds(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Collections.emptyList();
        }
        String placeholders = String.join(",", Collections.nCopies(taskIds.size(), "?"));
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM third_platform_procurement_execution "
                        + "WHERE del_flag = 0 AND task_id IN (" + placeholders + ") ORDER BY id ASC",
                ROW_MAPPER, taskIds.toArray());
    }

    public List<ThirdPlatformProcurementExecution> listByShopStatus(
            String shopName, ProcurementExecutionStatus status) {
        if (StringUtils.isBlank(shopName) || status == null) {
            return Collections.emptyList();
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM third_platform_procurement_execution "
                        + "WHERE shop_name = ? AND execution_status = ? AND del_flag = 0 ORDER BY id ASC",
                ROW_MAPPER, shopName, status.name());
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
