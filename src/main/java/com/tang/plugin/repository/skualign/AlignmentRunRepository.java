package com.tang.plugin.repository.skualign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tang.plugin.domain.entity.skualign.AlignmentRun;
import com.tang.plugin.enums.skualign.AlignmentRunStatus;
import com.tang.plugin.enums.skualign.AlignmentTriggerType;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
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
public class AlignmentRunRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final RowMapper<AlignmentRun> ROW_MAPPER = (rs, rowNum) -> new AlignmentRun()
            .setId(rs.getLong("id"))
            .setShopName(rs.getString("shop_name"))
            .setTriggerType(AlignmentTriggerType.valueOf(rs.getString("trigger_type")))
            .setScopeType(rs.getString("scope_type"))
            .setScopeIdsJson(rs.getString("scope_ids_json"))
            .setEngineVersion(rs.getString("engine_version"))
            .setRunStatus(AlignmentRunStatus.valueOf(rs.getString("run_status")))
            .setMatchedCount(rs.getInt("matched_count"))
            .setSuggestedCount(rs.getInt("suggested_count"))
            .setUnmappedCount(rs.getInt("unmapped_count"))
            .setNoSourceCount(rs.getInt("no_source_count"))
            .setBlockedCount(rs.getInt("blocked_count"))
            .setFailedCount(rs.getInt("failed_count"))
            .setStartedAt(toInstant(rs.getTimestamp("started_at")))
            .setFinishedAt(toInstant(rs.getTimestamp("finished_at")))
            .setErrorSummary(rs.getString("error_summary"))
            .setDelFlag(rs.getInt("del_flag"))
            .setCreatedAt(toInstant(rs.getTimestamp("created_at")));

    @Resource
    private JdbcTemplate jdbcTemplate;

    public long insertQueued(AlignmentRun run) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        Instant now = Instant.now();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    """
                    INSERT INTO alignment_run (
                      shop_name, trigger_type, scope_type, scope_ids_json, engine_version,
                      run_status, matched_count, suggested_count, unmapped_count, no_source_count,
                      blocked_count, failed_count, created_at, del_flag
                    ) VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, 0, 0, 0, ?, 0)
                    """,
                    new String[] { "id" });
            ps.setString(1, run.getShopName());
            ps.setString(2, run.getTriggerType().name());
            ps.setString(3, run.getScopeType());
            ps.setString(4, run.getScopeIdsJson());
            ps.setString(5, run.getEngineVersion() != null ? run.getEngineVersion() : "rule-v1");
            ps.setString(6, AlignmentRunStatus.QUEUED.name());
            ps.setTimestamp(7, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null && keyHolder.getKeys() != null && keyHolder.getKeys().containsKey("id")) {
            key = (Number) keyHolder.getKeys().get("id");
        }
        return key != null ? key.longValue() : 0L;
    }

    public Optional<AlignmentRun> findById(long id, String shopName) {
        try {
            AlignmentRun run = jdbcTemplate.queryForObject(
                    "SELECT * FROM alignment_run WHERE id = ? AND shop_name = ? AND del_flag = 0",
                    ROW_MAPPER, id, shopName);
            return Optional.ofNullable(run);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<AlignmentRun> findLatestForProduct(String shopName, String productId) {
        List<AlignmentRun> rows = jdbcTemplate.query(
                """
                SELECT * FROM alignment_run
                WHERE shop_name = ? AND del_flag = 0
                  AND scope_ids_json LIKE ?
                ORDER BY created_at DESC
                LIMIT 1
                """,
                ROW_MAPPER, shopName, "%\"" + productId + "\"%");
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void markRunning(long runId) {
        jdbcTemplate.update(
                "UPDATE alignment_run SET run_status = ?, started_at = ? WHERE id = ?",
                AlignmentRunStatus.RUNNING.name(), Timestamp.from(Instant.now()), runId);
    }

    public void finish(long runId, AlignmentRunStatus status, AlignmentRun run) {
        jdbcTemplate.update(
                """
                UPDATE alignment_run SET run_status = ?, matched_count = ?, suggested_count = ?,
                  unmapped_count = ?, no_source_count = ?, blocked_count = ?, failed_count = ?,
                  error_summary = ?, finished_at = ?
                WHERE id = ?
                """,
                status.name(),
                run.getMatchedCount(),
                run.getSuggestedCount(),
                run.getUnmappedCount(),
                run.getNoSourceCount(),
                run.getBlockedCount(),
                run.getFailedCount(),
                run.getErrorSummary(),
                Timestamp.from(Instant.now()),
                runId);
    }

    public static String scopeIdsJson(List<String> ids) {
        try {
            return JSON.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
