package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.user.UserWelcomeClaim;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 欢迎分领取记录 Repository（§4.2）。user_id 为主键保证幂等。
 */
@Slf4j
@Repository
public class UserWelcomeClaimRepository {

    private static final RowMapper<UserWelcomeClaim> ROW_MAPPER = (rs, rowNum) -> {
        UserWelcomeClaim c = new UserWelcomeClaim()
                .setUserId(rs.getLong("user_id"));
        Timestamp claimed = rs.getTimestamp("claimed_at");
        c.setClaimedAt(claimed != null ? claimed.toInstant() : null);
        return c;
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 幂等插入；已存在返回 false（不报错）。 */
    public boolean insertIfAbsent(Long userId) {
        try {
            int updated = jdbcTemplate.update(
                    "INSERT INTO user_welcome_claims (user_id, claimed_at) VALUES (?, ?)",
                    userId, Timestamp.from(Instant.now()));
            return updated > 0;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public boolean exists(Long userId) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_welcome_claims WHERE user_id = ?",
                Integer.class, userId);
        return cnt != null && cnt > 0;
    }
}
