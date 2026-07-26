package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.marketing.CompetitorStore;
import jakarta.annotation.Resource;
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
public class CompetitorStoreRepository {

    private static final RowMapper<CompetitorStore> ROW_MAPPER = (rs, rowNum) -> {
        CompetitorStore c = new CompetitorStore()
                .setId(rs.getLong("id"))
                .setUserId(rs.getLong("user_id"))
                .setStoreId(rs.getString("store_id"))
                .setStoreName(rs.getString("store_name"));
        Timestamp created = rs.getTimestamp("created_at");
        c.setCreatedAt(created != null ? created.toInstant() : null);
        Timestamp updated = rs.getTimestamp("updated_at");
        c.setUpdatedAt(updated != null ? updated.toInstant() : null);
        return c;
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    public CompetitorStore upsert(Long userId, String storeId, String storeName) {
        Instant now = Instant.now();
        Optional<CompetitorStore> existing = findByUserIdAndStoreId(userId, storeId);
        if (existing.isPresent()) {
            CompetitorStore row = existing.get();
            jdbcTemplate.update(
                    "UPDATE user_competitor_store SET store_name = ?, updated_at = ? WHERE id = ?",
                    storeName, Timestamp.from(now), row.getId());
            row.setStoreName(storeName);
            row.setUpdatedAt(now);
            return row;
        }
        String sql = "INSERT INTO user_competitor_store (user_id, store_id, store_name, created_at, updated_at) VALUES (?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setLong(1, userId);
            ps.setString(2, storeId);
            ps.setString(3, storeName);
            ps.setTimestamp(4, Timestamp.from(now));
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to retrieve generated id for user_competitor_store insert");
        }
        return new CompetitorStore()
                .setId(key.longValue())
                .setUserId(userId)
                .setStoreId(storeId)
                .setStoreName(storeName)
                .setCreatedAt(now)
                .setUpdatedAt(now);
    }

    public Optional<CompetitorStore> findByUserIdAndStoreId(Long userId, String storeId) {
        if (userId == null || storeId == null || storeId.isBlank()) return Optional.empty();
        try {
            CompetitorStore c = jdbcTemplate.queryForObject(
                    "SELECT id, user_id, store_id, store_name, created_at, updated_at FROM user_competitor_store WHERE user_id = ? AND store_id = ?",
                    ROW_MAPPER, userId, storeId);
            return Optional.ofNullable(c);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<CompetitorStore> listByUserId(Long userId) {
        if (userId == null) return List.of();
        return jdbcTemplate.query(
                "SELECT id, user_id, store_id, store_name, created_at, updated_at FROM user_competitor_store WHERE user_id = ? ORDER BY updated_at DESC",
                ROW_MAPPER, userId);
    }

    public int deleteByUserIdAndStoreId(Long userId, String storeId) {
        return jdbcTemplate.update(
                "DELETE FROM user_competitor_store WHERE user_id = ? AND store_id = ?",
                userId, storeId);
    }
}
