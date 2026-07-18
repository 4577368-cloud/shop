package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.order.ThirdPlatformOrder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * JDBC repository for {@code third_platform_order}. Idempotent by
 * (shop_type, shop_name, outer_order_id); queries scope to del_flag = 0. No snapshot refresh in P1.
 */
@Slf4j
@Repository
public class ThirdPlatformOrderRepository {

    private static final RowMapper<ThirdPlatformOrder> ROW_MAPPER = (rs, rowNum) -> new ThirdPlatformOrder()
            .setId(rs.getLong("id"))
            .setShopName(rs.getString("shop_name"))
            .setShopType(rs.getString("shop_type"))
            .setOuterOrderId(rs.getString("outer_order_id"))
            .setOrderName(rs.getString("order_name"))
            .setFinancialStatus(rs.getString("financial_status"))
            .setFulfillmentStatus(rs.getString("fulfillment_status"))
            .setCurrency(rs.getString("currency"))
            .setTotalPrice(rs.getBigDecimal("total_price"))
            .setPlatformCreatedAt(toInstant(rs.getTimestamp("platform_created_at")))
            .setPlatformUpdatedAt(toInstant(rs.getTimestamp("platform_updated_at")))
            .setDelFlag(rs.getInt("del_flag"))
            .setCreatedAt(toInstant(rs.getTimestamp("created_at")))
            .setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));

    private static final String COLUMNS = """
            id, shop_name, shop_type, outer_order_id, order_name, financial_status, fulfillment_status,
            currency, total_price, platform_created_at, platform_updated_at, del_flag, created_at, updated_at
            """;

    @Resource
    private JdbcTemplate jdbcTemplate;

    public Long findIdByKey(String shopType, String shopName, String outerOrderId) {
        if (StringUtils.isAnyBlank(shopType, shopName, outerOrderId)) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM third_platform_order "
                            + "WHERE shop_type = ? AND shop_name = ? AND outer_order_id = ? AND del_flag = 0",
                    Long.class, shopType, shopName, outerOrderId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public List<Long> listIdsByKey(String shopType, String shopName, String outerOrderId) {
        if (StringUtils.isAnyBlank(shopType, shopName, outerOrderId)) {
            return Collections.emptyList();
        }
        return jdbcTemplate.queryForList(
                "SELECT id FROM third_platform_order "
                        + "WHERE shop_type = ? AND shop_name = ? AND outer_order_id = ? AND del_flag = 0",
                Long.class, shopType, shopName, outerOrderId);
    }

    /**
     * Return existing header id if present; otherwise insert. On unique-key race, re-read and return
     * the existing id instead of propagating the duplicate exception. Never refreshes snapshot fields.
     */
    public Long saveIfAbsent(ThirdPlatformOrder order) {
        Long existing = findIdByKey(order.getShopType(), order.getShopName(), order.getOuterOrderId());
        if (existing != null) {
            log.info("Order header idempotent hit id={} shopName={} outerOrderId={}",
                    existing, order.getShopName(), order.getOuterOrderId());
            return existing;
        }
        try {
            Long id = insert(order);
            log.info("Order header inserted id={} shopName={} outerOrderId={}",
                    id, order.getShopName(), order.getOuterOrderId());
            return id;
        } catch (DuplicateKeyException e) {
            Long raced = findIdByKey(order.getShopType(), order.getShopName(), order.getOuterOrderId());
            log.info("Order header insert race resolved id={} shopName={} outerOrderId={}",
                    raced, order.getShopName(), order.getOuterOrderId());
            return raced;
        }
    }

    public Optional<ThirdPlatformOrder> findByOuterOrderId(String shopName, String outerOrderId) {
        if (StringUtils.isAnyBlank(shopName, outerOrderId)) {
            return Optional.empty();
        }
        try {
            ThirdPlatformOrder order = jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM third_platform_order "
                            + "WHERE shop_name = ? AND outer_order_id = ? AND del_flag = 0",
                    ROW_MAPPER, shopName, outerOrderId);
            return Optional.ofNullable(order);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<ThirdPlatformOrder> listByShop(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            return Collections.emptyList();
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM third_platform_order "
                        + "WHERE shop_name = ? AND del_flag = 0 ORDER BY id DESC",
                ROW_MAPPER, shopName);
    }

    private Long insert(ThirdPlatformOrder order) {
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO third_platform_order
                    (shop_name, shop_type, outer_order_id, order_name, financial_status, fulfillment_status,
                     currency, total_price, platform_created_at, platform_updated_at, del_flag, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """,
                    new String[]{"id"});
            ps.setString(1, order.getShopName());
            ps.setString(2, order.getShopType());
            ps.setString(3, order.getOuterOrderId());
            ps.setString(4, order.getOrderName());
            ps.setString(5, order.getFinancialStatus());
            ps.setString(6, order.getFulfillmentStatus());
            ps.setString(7, order.getCurrency());
            if (order.getTotalPrice() == null) {
                ps.setNull(8, java.sql.Types.DECIMAL);
            } else {
                ps.setBigDecimal(8, order.getTotalPrice());
            }
            ps.setTimestamp(9, toTimestamp(order.getPlatformCreatedAt()));
            ps.setTimestamp(10, toTimestamp(order.getPlatformUpdatedAt()));
            ps.setTimestamp(11, Timestamp.from(now));
            ps.setTimestamp(12, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
