package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.user.UserRefreshToken;
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
public class UserRefreshTokenRepository {

    private static final RowMapper<UserRefreshToken> ROW_MAPPER = (rs, rowNum) -> {
        UserRefreshToken t = new UserRefreshToken()
                .setId(rs.getLong("id"))
                .setUserId(rs.getLong("user_id"))
                .setTokenHash(rs.getString("token_hash"))
                .setUserAgent(rs.getString("user_agent"))
                .setIp(rs.getString("ip"))
                .setDelFlag(rs.getInt("del_flag"));
        Timestamp exp = rs.getTimestamp("expires_at");
        t.setExpiresAt(exp != null ? exp.toInstant() : null);
        Timestamp created = rs.getTimestamp("created_at");
        t.setCreatedAt(created != null ? created.toInstant() : null);
        return t;
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    public UserRefreshToken insert(UserRefreshToken token) {
        String sql = """
                INSERT INTO user_refresh_token (user_id, token_hash, expires_at, created_at, user_agent, ip, del_flag)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        Instant now = Instant.now();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setLong(1, token.getUserId());
            ps.setString(2, token.getTokenHash());
            ps.setTimestamp(3, Timestamp.from(token.getExpiresAt()));
            ps.setTimestamp(4, Timestamp.from(now));
            ps.setString(5, token.getUserAgent());
            ps.setString(6, token.getIp());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to retrieve generated id for user_refresh_token insert");
        }
        token.setId(key.longValue());
        token.setCreatedAt(now);
        return token;
    }

    public Optional<UserRefreshToken> findActiveByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) return Optional.empty();
        try {
            UserRefreshToken t = jdbcTemplate.queryForObject(
                    """
                    SELECT id, user_id, token_hash, expires_at, created_at, user_agent, ip, del_flag
                    FROM user_refresh_token
                    WHERE token_hash = ? AND del_flag = 0
                    """,
                    ROW_MAPPER,
                    tokenHash);
            return Optional.ofNullable(t);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void revokeByTokenHash(String tokenHash) {
        jdbcTemplate.update(
                "UPDATE user_refresh_token SET del_flag = 1 WHERE token_hash = ?",
                tokenHash);
    }

    public void revokeAllByUserId(Long userId) {
        jdbcTemplate.update(
                "UPDATE user_refresh_token SET del_flag = 1 WHERE user_id = ? AND del_flag = 0",
                userId);
    }
}
