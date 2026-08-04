package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.logistics.LogisticsTemplate;
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
import java.util.Optional;

@Slf4j
@Repository
public class LogisticsTemplateRepository {

    private static final RowMapper<LogisticsTemplate> ROW_MAPPER = (rs, rowNum) -> new LogisticsTemplate()
            .setId(rs.getLong("id"))
            .setShopName(rs.getString("shop_name"))
            .setPackaging(rs.getString("packaging"))
            .setSpeedPreference(rs.getString("speed_preference"))
            .setMarketsJson(rs.getString("markets_json"))
            .setDeclareJson(rs.getString("declare_json"))
            .setDelFlag(rs.getInt("del_flag"))
            .setCreatedAt(toInstant(rs.getTimestamp("created_at")))
            .setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));

    @Resource
    private JdbcTemplate jdbcTemplate;

    private volatile boolean declareColumnReady = false;

    /** Prod may skip schema.sql — add declare_json on first use. */
    private void ensureDeclareColumn() {
        if (declareColumnReady) return;
        synchronized (this) {
            if (declareColumnReady) return;
            try {
                jdbcTemplate.execute(
                        "ALTER TABLE logistics_template ADD COLUMN IF NOT EXISTS declare_json TEXT");
            } catch (Exception e) {
                try {
                    jdbcTemplate.execute(
                            "ALTER TABLE logistics_template ADD COLUMN declare_json TEXT");
                } catch (Exception ignored) {
                    /* column may already exist */
                }
            }
            declareColumnReady = true;
        }
    }

    public Optional<LogisticsTemplate> findByShop(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            return Optional.empty();
        }
        ensureDeclareColumn();
        try {
            LogisticsTemplate row = jdbcTemplate.queryForObject(
                    """
                    SELECT id, shop_name, packaging, speed_preference, markets_json, declare_json,
                           del_flag, created_at, updated_at
                    FROM logistics_template
                    WHERE shop_name = ? AND del_flag = 0
                    """,
                    ROW_MAPPER, shopName);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public LogisticsTemplate upsert(LogisticsTemplate template) {
        ensureDeclareColumn();
        Instant now = Instant.now();
        Optional<LogisticsTemplate> existing = findByShop(template.getShopName());
        if (existing.isPresent()) {
            Long id = existing.get().getId();
            jdbcTemplate.update(
                    """
                    UPDATE logistics_template
                    SET packaging = ?, speed_preference = ?, markets_json = ?, declare_json = ?,
                        updated_at = ?, del_flag = 0
                    WHERE id = ?
                    """,
                    template.getPackaging(),
                    template.getSpeedPreference(),
                    template.getMarketsJson(),
                    template.getDeclareJson(),
                    Timestamp.from(now),
                    id);
            return findByShop(template.getShopName()).orElseThrow();
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO logistics_template
                    (shop_name, packaging, speed_preference, markets_json, declare_json,
                     del_flag, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 0, ?, ?)
                    """,
                    new String[]{"id"});
            ps.setString(1, template.getShopName());
            ps.setString(2, template.getPackaging());
            ps.setString(3, template.getSpeedPreference());
            ps.setString(4, template.getMarketsJson());
            ps.setString(5, template.getDeclareJson());
            ps.setTimestamp(6, Timestamp.from(now));
            ps.setTimestamp(7, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return findByShop(template.getShopName()).orElseThrow();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
