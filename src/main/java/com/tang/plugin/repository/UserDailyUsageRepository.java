package com.tang.plugin.repository;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;

/**
 * 用户每日付费 API 调用计数（§2.1 日调用上限）。
 *
 * <p>定稿 §2.1：Starter 80次/日、Growth 200次/日、无订阅 5次/日。
 * 每次 {@code MarketingController.handleMarketing} 调用付费端点前 increment + check。
 */
@Slf4j
@Repository
public class UserDailyUsageRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 获取用户今日已调用次数。不存在返回 0。
     */
    public int getTodayCount(Long userId) {
        try {
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT call_count FROM user_daily_usage WHERE user_id = ? AND usage_date = ?",
                    Integer.class, userId, Date.valueOf(LocalDate.now()));
            return cnt != null ? cnt : 0;
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return 0;
        }
    }

    /**
     * 原子递增今日调用计数（UPSERT）。返回递增后的值。
     */
    public int incrementToday(Long userId) {
        LocalDate today = LocalDate.now();
        // PostgreSQL UPSERT
        int updated = jdbcTemplate.update(
                """
                INSERT INTO user_daily_usage (user_id, usage_date, call_count, created_at, updated_at)
                VALUES (?, ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (user_id, usage_date) DO UPDATE
                    SET call_count = user_daily_usage.call_count + 1,
                        updated_at = CURRENT_TIMESTAMP
                """,
                userId, Date.valueOf(today));
        if (updated == 0) {
            // H2 兼容回退
            jdbcTemplate.update(
                    """
                    MERGE INTO user_daily_usage (user_id, usage_date, call_count, created_at, updated_at)
                    KEY (user_id, usage_date)
                    VALUES (?, ?, COALESCE((SELECT call_count + 1 FROM user_daily_usage WHERE user_id = ? AND usage_date = ?), 1), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    userId, Date.valueOf(today), userId, Date.valueOf(today));
        }
        return getTodayCount(userId);
    }
}
