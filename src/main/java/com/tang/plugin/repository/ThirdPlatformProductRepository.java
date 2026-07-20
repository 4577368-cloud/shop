package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * JDBC mirror repository for {@code third_platform_product}. Upsert by (shop_name, item_id).
 */
@Slf4j
@Repository
public class ThirdPlatformProductRepository {

    private static final RowMapper<ThirdPlatformProduct> ROW_MAPPER = (rs, rowNum) -> new ThirdPlatformProduct()
            .setId(rs.getLong("id"))
            .setShopName(rs.getString("shop_name"))
            .setShopType(rs.getString("shop_type"))
            .setThirdPlatformItemId(rs.getString("third_platform_item_id"))
            .setHandle(rs.getString("handle"))
            .setTitle(rs.getString("title"))
            .setDescription(rs.getString("description"))
            .setStatus(rs.getString("status"))
            .setCurrency(rs.getString("currency"))
            .setMinPrice(rs.getBigDecimal("min_price"))
            .setMaxPrice(rs.getBigDecimal("max_price"))
            .setMinPriceLocal(rs.getBigDecimal("min_price_local"))
            .setMaxPriceLocal(rs.getBigDecimal("max_price_local"))
            .setMinWeightGrams((Double) rs.getObject("min_weight_grams"))
            .setMaxWeightGrams((Double) rs.getObject("max_weight_grams"))
            .setPrimaryImageUrl(rs.getString("primary_image_url"))
            .setUpdatedAt(toInstant(rs.getTimestamp("updated_at")))
            .setDelFlag(rs.getInt("del_flag"));

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * List active SPU mirror rows for a shop (del_flag = 0), most recently updated first.
     */
    public List<ThirdPlatformProduct> listByShop(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT id, shop_name, shop_type, third_platform_item_id, handle, title, description, status,
                       currency, min_price, max_price, min_price_local, max_price_local,
                       min_weight_grams, max_weight_grams, primary_image_url, updated_at, del_flag
                FROM third_platform_product
                WHERE shop_name = ? AND del_flag = 0
                ORDER BY updated_at DESC NULLS LAST, id DESC
                """,
                ROW_MAPPER,
                shopName);
    }

    /**
     * Count active SPU mirror rows for a shop (del_flag = 0).
     */
    public int countByShop(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            return 0;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM third_platform_product WHERE shop_name = ? AND del_flag = 0",
                Integer.class,
                shopName);
        return count == null ? 0 : count;
    }

    /**
     * Upsert SPU mirror keyed by (shop_name, third_platform_item_id).
     */
    public void upsert(ThirdPlatformProduct product) {
        Long id = findId(product.getShopName(), product.getThirdPlatformItemId());
        if (id != null) {
            jdbcTemplate.update(
                    """
                    UPDATE third_platform_product
                    SET shop_type = ?, handle = ?, title = ?, description = ?, status = ?, currency = ?,
                        min_price = ?, max_price = ?, min_price_local = ?, max_price_local = ?,
                        min_weight_grams = ?, max_weight_grams = ?, primary_image_url = ?,
                        updated_at = ?, del_flag = 0
                    WHERE id = ?
                    """,
                    product.getShopType(),
                    product.getHandle(),
                    product.getTitle(),
                    product.getDescription(),
                    product.getStatus(),
                    product.getCurrency(),
                    product.getMinPrice(),
                    product.getMaxPrice(),
                    product.getMinPriceLocal(),
                    product.getMaxPriceLocal(),
                    product.getMinWeightGrams(),
                    product.getMaxWeightGrams(),
                    product.getPrimaryImageUrl(),
                    toTimestamp(product.getUpdatedAt()),
                    id);
            product.setId(id);
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO third_platform_product
                (shop_name, shop_type, third_platform_item_id, handle, title, description, status, currency,
                 min_price, max_price, min_price_local, max_price_local, min_weight_grams, max_weight_grams,
                 primary_image_url, updated_at, del_flag)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                product.getShopName(),
                product.getShopType(),
                product.getThirdPlatformItemId(),
                product.getHandle(),
                product.getTitle(),
                product.getDescription(),
                product.getStatus(),
                product.getCurrency(),
                product.getMinPrice(),
                product.getMaxPrice(),
                product.getMinPriceLocal(),
                product.getMaxPriceLocal(),
                product.getMinWeightGrams(),
                product.getMaxWeightGrams(),
                product.getPrimaryImageUrl(),
                toTimestamp(product.getUpdatedAt()));
    }

    /**
     * Soft-delete one SPU mirror row. Returns rows updated (0 or 1).
     */
    public int softDelete(String shopName, String itemId) {
        if (StringUtils.isAnyBlank(shopName, itemId)) {
            return 0;
        }
        return jdbcTemplate.update(
                """
                UPDATE third_platform_product
                SET del_flag = 1, updated_at = ?
                WHERE shop_name = ? AND third_platform_item_id = ? AND del_flag = 0
                """,
                Timestamp.from(Instant.now()),
                shopName,
                itemId);
    }

    /**
     * Active product GIDs for a shop (del_flag = 0), used by full-sync reconcile.
     */
    public List<String> listActiveItemIds(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            return List.of();
        }
        return jdbcTemplate.queryForList(
                """
                SELECT third_platform_item_id
                FROM third_platform_product
                WHERE shop_name = ? AND del_flag = 0
                """,
                String.class,
                shopName);
    }

    private Long findId(String shopName, String itemId) {
        if (StringUtils.isAnyBlank(shopName, itemId)) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM third_platform_product WHERE shop_name = ? AND third_platform_item_id = ?",
                    Long.class,
                    shopName, itemId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
