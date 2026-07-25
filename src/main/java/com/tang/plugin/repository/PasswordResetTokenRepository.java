package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.user.PasswordResetToken;
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
import java.util.Optional;

@Slf4j
@Repository
public class PasswordResetTokenRepository {

    private static final RowMapper<PasswordResetToken> ROW_MAPPER = (rs, rowNum) -> {
        PasswordResetToken t = new PasswordResetToken()
                .setId(rs.getLong("id"))
                .setUserId(rs.getLong("user_id"))
                .setTokenHash(rs.getString("token_hash"))
                .setIp(rs.getString("ip"))
                .setUserAgent(rs.getString("user_agent"));
        Timestamp exp = rs.getTimestamp("expires_at");
        t.setExpiresAt(exp != null ? exp.toInstant() : null);
        Timestamp used = rs.getTimestamp("used_at");
        t.setUsedAt(used != null ? used.toInstant() : null);
        Timestamp created = rs.getTimestamp("created_at");
        t.setCreatedAt(created != null ? created.toInstant() : null);
        return t;
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    public PasswordResetToken insert(PasswordResetToken token) {
        String sql = """
                INSERT INTO user_password_reset_tokens (user_id, token_hash, expires_at, used_at, ip, user_agent, created_at)
                VALUES (?, ?, ?, NULL, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        Instant now = Instant.now();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setLong(1, token.getUserId());
            ps.setString(2, token.getTokenHash());
            ps.setTimestamp(3, Timestamp.from(token.getExpiresAt()));
            ps.setString(4, token.getIp());
            ps.setString(5, token.getUserAgent());
            ps.setTimestamp(6, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) token.setId(key.longValue());
        token.setCreatedAt(now);
        return token;
    }

    public Optional<PasswordResetToken> findByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) return Optional.empty();
        try {
            PasswordResetToken t = jdbcTemplate.queryForObject(
                    """
                    SELECT id, user_id, token_hash, expires_at, used_at, ip, user_agent, created_at
                    FROM user_password_reset_tokens
                    WHERE token_hash = ?
                    """,
                    ROW_MAPPER,
                    tokenHash);
            return Optional.ofNullable(t);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 标记 token 已使用（一次性）。仅当 used_at 为 null 时更新。
     * 返回影响行数（1=成功标记，0=已被其他请求使用）。
     */
    public int markUsed(String tokenHash) {
        return jdbcTemplate.update(
                """
                UPDATE user_password_reset_tokens
                SET used_at = ?
                WHERE token_hash = ? AND used_at IS NULL
                """,
                Timestamp.from(Instant.now()), tokenHash);
    }

    /**
     * 清理过期且未使用的 token（定时任务调用，避免表膨胀）。
     * 返回删除行数。
     */
    public int deleteExpiredUnused() {
        return jdbcTemplate.update(
                """
                DELETE FROM user_password_reset_tokens
                WHERE expires_at < ? AND used_at IS NULL
                """,
                Timestamp.from(Instant.now()));
    }
}
