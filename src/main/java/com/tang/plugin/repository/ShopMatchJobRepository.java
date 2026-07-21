package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.match.ShopMatchJob;
import com.tang.plugin.enums.match.MatchJobStatus;
import jakarta.annotation.Resource;
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

@Repository
public class ShopMatchJobRepository {

    private static final String COLUMNS = """
            id, shop_name, job_type, status, scope_item_id, scope_item_ids_json,
            total_count, processed_count, linked_count, skipped_count, failed_count,
            last_error, recent_json, started_at, finished_at, created_at, updated_at, del_flag
            """;

    private static final RowMapper<ShopMatchJob> ROW_MAPPER = (rs, rowNum) -> new ShopMatchJob()
            .setId(rs.getLong("id"))
            .setShopName(rs.getString("shop_name"))
            .setJobType(rs.getString("job_type"))
            .setStatus(MatchJobStatus.valueOf(rs.getString("status")))
            .setScopeItemId(rs.getString("scope_item_id"))
            .setScopeItemIdsJson(rs.getString("scope_item_ids_json"))
            .setTotalCount(rs.getInt("total_count"))
            .setProcessedCount(rs.getInt("processed_count"))
            .setLinkedCount(rs.getInt("linked_count"))
            .setSkippedCount(rs.getInt("skipped_count"))
            .setFailedCount(rs.getInt("failed_count"))
            .setLastError(rs.getString("last_error"))
            .setRecentJson(rs.getString("recent_json"))
            .setStartedAt(toInstant(rs.getTimestamp("started_at")))
            .setFinishedAt(toInstant(rs.getTimestamp("finished_at")))
            .setCreatedAt(toInstant(rs.getTimestamp("created_at")))
            .setUpdatedAt(toInstant(rs.getTimestamp("updated_at")))
            .setDelFlag(rs.getInt("del_flag"));

    @Resource
    private JdbcTemplate jdbcTemplate;

    public Optional<ShopMatchJob> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        try {
            ShopMatchJob row = jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM shop_match_job WHERE id = ? AND del_flag = 0",
                    ROW_MAPPER, id);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<ShopMatchJob> findActiveByShop(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            return Optional.empty();
        }
        try {
            ShopMatchJob row = jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM shop_match_job "
                            + "WHERE shop_name = ? AND status IN (?, ?) AND del_flag = 0 "
                            + "ORDER BY id DESC LIMIT 1",
                    ROW_MAPPER, shopName, MatchJobStatus.PENDING.name(), MatchJobStatus.RUNNING.name());
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Long insert(ShopMatchJob job) {
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO shop_match_job
                    (shop_name, job_type, status, scope_item_id, scope_item_ids_json,
                     total_count, processed_count, linked_count, skipped_count, failed_count,
                     last_error, recent_json, started_at, finished_at, created_at, updated_at, del_flag)
                    VALUES (?, ?, ?, ?, ?, 0, 0, 0, 0, 0, NULL, NULL, NULL, NULL, ?, ?, 0)
                    """,
                    new String[]{"id"});
            ps.setString(1, job.getShopName());
            ps.setString(2, StringUtils.defaultIfBlank(job.getJobType(), "IMAGE_AUTO_MATCH"));
            ps.setString(3, job.getStatus().name());
            ps.setString(4, job.getScopeItemId());
            ps.setString(5, job.getScopeItemIdsJson());
            ps.setTimestamp(6, Timestamp.from(now));
            ps.setTimestamp(7, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    public int markRunning(Long id, int totalCount) {
        Instant now = Instant.now();
        return jdbcTemplate.update(
                "UPDATE shop_match_job SET status = ?, total_count = ?, started_at = ?, updated_at = ? "
                        + "WHERE id = ? AND status = ? AND del_flag = 0",
                MatchJobStatus.RUNNING.name(), totalCount, Timestamp.from(now), Timestamp.from(now),
                id, MatchJobStatus.PENDING.name());
    }

    public int updateProgress(ShopMatchJob job) {
        Instant now = Instant.now();
        return jdbcTemplate.update(
                """
                UPDATE shop_match_job SET
                  processed_count = ?, linked_count = ?, skipped_count = ?, failed_count = ?,
                  last_error = ?, recent_json = ?, updated_at = ?
                WHERE id = ? AND del_flag = 0
                """,
                job.getProcessedCount(), job.getLinkedCount(), job.getSkippedCount(), job.getFailedCount(),
                job.getLastError(), job.getRecentJson(), Timestamp.from(now), job.getId());
    }

    public int markTerminal(Long id, MatchJobStatus status, String lastError) {
        Instant now = Instant.now();
        return jdbcTemplate.update(
                "UPDATE shop_match_job SET status = ?, last_error = ?, finished_at = ?, updated_at = ? "
                        + "WHERE id = ? AND del_flag = 0",
                status.name(), lastError, Timestamp.from(now), Timestamp.from(now), id);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
