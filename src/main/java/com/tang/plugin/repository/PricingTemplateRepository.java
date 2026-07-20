package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.pricing.PricingTemplate;
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

/**
 * JDBC repository for {@code pricing_template}. One active row per shop (uk_pricing_template_shop);
 * upsert keyed by shop_name where del_flag = 0.
 */
@Slf4j
@Repository
public class PricingTemplateRepository {

    private static final RowMapper<PricingTemplate> ROW_MAPPER = (rs, rowNum) -> new PricingTemplate()
            .setId(rs.getLong("id"))
            .setShopName(rs.getString("shop_name"))
            .setSourceCurrency(rs.getString("source_currency"))
            .setTargetCurrency(rs.getString("target_currency"))
            .setExchangeRate((Double) rs.getObject("exchange_rate"))
            .setMultiplier((Double) rs.getObject("multiplier"))
            .setAddend((Double) rs.getObject("addend"))
            .setRoundingStrategy(rs.getString("rounding_strategy"))
            .setDecimals((Integer) rs.getObject("decimals"))
            .setCreatedAt(toInstant(rs.getTimestamp("created_at")))
            .setUpdatedAt(toInstant(rs.getTimestamp("updated_at")))
            .setDelFlag(rs.getInt("del_flag"));

    @Resource
    private JdbcTemplate jdbcTemplate;

    public Optional<PricingTemplate> findByShop(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            return Optional.empty();
        }
        try {
            PricingTemplate template = jdbcTemplate.queryForObject(
                    """
                    SELECT id, shop_name, source_currency, target_currency, exchange_rate, multiplier,
                           addend, rounding_strategy, decimals, created_at, updated_at, del_flag
                    FROM pricing_template
                    WHERE shop_name = ? AND del_flag = 0
                    """,
                    ROW_MAPPER,
                    shopName);
            return Optional.ofNullable(template);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Upsert the shop's active template. Returns the persisted row with id/timestamps populated.
     */
    public PricingTemplate upsert(PricingTemplate template) {
        Instant now = Instant.now();
        Optional<PricingTemplate> existing = findByShop(template.getShopName());
        if (existing.isPresent()) {
            Long id = existing.get().getId();
            jdbcTemplate.update(
                    """
                    UPDATE pricing_template
                    SET source_currency = ?, target_currency = ?, exchange_rate = ?, multiplier = ?,
                        addend = ?, rounding_strategy = ?, decimals = ?, updated_at = ?, del_flag = 0
                    WHERE id = ?
                    """,
                    template.getSourceCurrency(),
                    template.getTargetCurrency(),
                    template.getExchangeRate(),
                    template.getMultiplier(),
                    template.getAddend(),
                    template.getRoundingStrategy(),
                    template.getDecimals(),
                    Timestamp.from(now),
                    id);
            return findByShop(template.getShopName()).orElseThrow();
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO pricing_template
                    (shop_name, source_currency, target_currency, exchange_rate, multiplier, addend,
                     rounding_strategy, decimals, created_at, updated_at, del_flag)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """,
                    new String[]{"id"});
            ps.setString(1, template.getShopName());
            ps.setString(2, template.getSourceCurrency());
            ps.setString(3, template.getTargetCurrency());
            ps.setObject(4, template.getExchangeRate());
            ps.setObject(5, template.getMultiplier());
            ps.setObject(6, template.getAddend());
            ps.setString(7, template.getRoundingStrategy());
            ps.setObject(8, template.getDecimals());
            ps.setTimestamp(9, Timestamp.from(now));
            ps.setTimestamp(10, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return findByShop(template.getShopName()).orElseThrow();
    }

    /** Soft-delete active row(s) for a shop so getEffective returns the system default. */
    public void softDeleteByShop(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            return;
        }
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                UPDATE pricing_template
                SET del_flag = 1, updated_at = ?
                WHERE shop_name = ? AND del_flag = 0
                """,
                Timestamp.from(now),
                shopName);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
