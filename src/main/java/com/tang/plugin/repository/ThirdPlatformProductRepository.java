package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * JDBC mirror repository for {@code third_platform_product}. Upsert by (shop_name, item_id).
 */
@Slf4j
@Repository
public class ThirdPlatformProductRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

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
}
