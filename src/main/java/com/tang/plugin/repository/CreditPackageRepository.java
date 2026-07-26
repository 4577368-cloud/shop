package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.user.CreditPackage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 积分加购包 Repository（§3）。
 */
@Slf4j
@Repository
public class CreditPackageRepository {

    private static final RowMapper<CreditPackage> ROW_MAPPER = (rs, rowNum) -> {
        CreditPackage p = new CreditPackage()
                .setId(rs.getLong("id"))
                .setCode(rs.getString("code"))
                .setName(rs.getString("name"))
                .setPriceUsdCents(rs.getLong("price_usd_cents"))
                .setCreditsNormal(rs.getInt("credits_normal"))
                .setCreditsPromo(rs.getInt("credits_promo"))
                .setDurationDays(rs.getInt("duration_days"))
                .setSortOrder(rs.getInt("sort_order"))
                .setActive(rs.getBoolean("active"));
        Timestamp pu = rs.getTimestamp("promo_until");
        p.setPromoUntil(pu != null ? pu.toInstant() : null);
        return p;
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    public List<CreditPackage> listActive() {
        return jdbcTemplate.query(
                "SELECT id, code, name, price_usd_cents, credits_normal, credits_promo, "
                        + "promo_until, duration_days, sort_order, active "
                        + "FROM credit_packages WHERE active = TRUE ORDER BY sort_order ASC, id ASC",
                ROW_MAPPER);
    }

    public CreditPackage findByCode(String code) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, code, name, price_usd_cents, credits_normal, credits_promo, "
                            + "promo_until, duration_days, sort_order, active "
                            + "FROM credit_packages WHERE code = ?",
                    ROW_MAPPER, code);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }
}
