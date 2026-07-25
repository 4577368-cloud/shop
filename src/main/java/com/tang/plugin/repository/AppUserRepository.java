package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.user.AppUser;
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
public class AppUserRepository {

    private static final RowMapper<AppUser> ROW_MAPPER = (rs, rowNum) -> {
        AppUser u = new AppUser()
                .setId(rs.getLong("id"))
                .setEmail(rs.getString("email"))
                .setPasswordHash(rs.getString("password_hash"))
                .setName(rs.getString("name"))
                .setAvatarUrl(rs.getString("avatar_url"))
                .setLocale(rs.getString("locale"))
                .setTimezone(rs.getString("timezone"))
                .setCurrency(rs.getString("currency"))
                .setAiResponseLanguage(rs.getString("ai_response_language"))
                .setStatus(rs.getString("status"))
                .setDelFlag(rs.getInt("del_flag"));
        Timestamp lastLogin = rs.getTimestamp("last_login_at");
        u.setLastLoginAt(lastLogin != null ? lastLogin.toInstant() : null);
        Timestamp created = rs.getTimestamp("created_at");
        u.setCreatedAt(created != null ? created.toInstant() : null);
        Timestamp updated = rs.getTimestamp("updated_at");
        u.setUpdatedAt(updated != null ? updated.toInstant() : null);
        return u;
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    public Optional<AppUser> findByEmail(String email) {
        if (StringUtils.isBlank(email)) return Optional.empty();
        try {
            AppUser u = jdbcTemplate.queryForObject(
                    """
                    SELECT id, email, password_hash, name, avatar_url, locale, timezone, currency,
                           ai_response_language, status, last_login_at, created_at, updated_at, del_flag
                    FROM app_user
                    WHERE email = ? AND del_flag = 0
                    """,
                    ROW_MAPPER,
                    email.trim().toLowerCase());
            return Optional.ofNullable(u);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<AppUser> findById(Long id) {
        if (id == null) return Optional.empty();
        try {
            AppUser u = jdbcTemplate.queryForObject(
                    """
                    SELECT id, email, password_hash, name, avatar_url, locale, timezone, currency,
                           ai_response_language, status, last_login_at, created_at, updated_at, del_flag
                    FROM app_user
                    WHERE id = ? AND del_flag = 0
                    """,
                    ROW_MAPPER,
                    id);
            return Optional.ofNullable(u);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public AppUser insert(AppUser user) {
        String sql = """
                INSERT INTO app_user (email, password_hash, name, avatar_url, locale, timezone, currency,
                                      ai_response_language, status, created_at, updated_at, del_flag)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        Instant now = Instant.now();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, user.getEmail());
            ps.setString(2, user.getPasswordHash());
            ps.setString(3, user.getName());
            ps.setString(4, user.getAvatarUrl());
            ps.setString(5, user.getLocale() != null ? user.getLocale() : "zh");
            ps.setString(6, user.getTimezone() != null ? user.getTimezone() : "Asia/Shanghai");
            ps.setString(7, user.getCurrency() != null ? user.getCurrency() : "CNY");
            ps.setString(8, user.getAiResponseLanguage() != null ? user.getAiResponseLanguage() : "zh");
            ps.setString(9, user.getStatus() != null ? user.getStatus() : "active");
            ps.setTimestamp(10, Timestamp.from(now));
            ps.setTimestamp(11, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to retrieve generated id for app_user insert");
        }
        user.setId(key.longValue());
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return user;
    }

    public void updatePasswordHash(Long id, String passwordHash) {
        jdbcTemplate.update(
                "UPDATE app_user SET password_hash = ?, updated_at = ? WHERE id = ?",
                passwordHash, Timestamp.from(Instant.now()), id);
    }

    public void updateLastLogin(Long id) {
        jdbcTemplate.update(
                "UPDATE app_user SET last_login_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), id);
    }
}
