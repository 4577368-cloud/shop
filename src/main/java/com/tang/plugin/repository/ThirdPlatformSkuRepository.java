package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC mirror repository for {@code third_platform_sku}. Upsert by (shop_name, sku_id); soft-delete only.
 */
@Slf4j
@Repository
public class ThirdPlatformSkuRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * Soft-delete all SKUs of a product so a re-sync can reactivate only current ones.
     */
    public int softDeleteByItem(String shopName, String itemId) {
        if (StringUtils.isAnyBlank(shopName, itemId)) {
            return 0;
        }
        return jdbcTemplate.update(
                "UPDATE third_platform_sku SET del_flag = 1 WHERE shop_name = ? AND third_platform_item_id = ?",
                shopName, itemId);
    }

    /**
     * Upsert SKU mirror keyed by (shop_name, third_platform_sku_id); sets del_flag = 0.
     */
    public void upsert(ThirdPlatformSku sku) {
        Long id = findId(sku.getShopName(), sku.getThirdPlatformSkuId());
        if (id != null) {
            jdbcTemplate.update(
                    """
                    UPDATE third_platform_sku
                    SET shop_type = ?, third_platform_item_id = ?, sku = ?, title = ?, price = ?, price_local = ?,
                        weight_grams = ?, option1 = ?, option2 = ?, option3 = ?, image_url = ?, barcode = ?,
                        inventory_quantity = ?, position = ?, del_flag = 0
                    WHERE id = ?
                    """,
                    sku.getShopType(),
                    sku.getThirdPlatformItemId(),
                    sku.getSku(),
                    sku.getTitle(),
                    sku.getPrice(),
                    sku.getPriceLocal(),
                    sku.getWeightGrams(),
                    sku.getOption1(),
                    sku.getOption2(),
                    sku.getOption3(),
                    sku.getImageUrl(),
                    sku.getBarcode(),
                    sku.getInventoryQuantity(),
                    sku.getPosition(),
                    id);
            sku.setId(id);
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO third_platform_sku
                (shop_name, shop_type, third_platform_item_id, third_platform_sku_id, sku, title, price, price_local,
                 weight_grams, option1, option2, option3, image_url, barcode, inventory_quantity, position, del_flag)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                sku.getShopName(),
                sku.getShopType(),
                sku.getThirdPlatformItemId(),
                sku.getThirdPlatformSkuId(),
                sku.getSku(),
                sku.getTitle(),
                sku.getPrice(),
                sku.getPriceLocal(),
                sku.getWeightGrams(),
                sku.getOption1(),
                sku.getOption2(),
                sku.getOption3(),
                sku.getImageUrl(),
                sku.getBarcode(),
                sku.getInventoryQuantity(),
                sku.getPosition());
    }

    private Long findId(String shopName, String skuId) {
        if (StringUtils.isAnyBlank(shopName, skuId)) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM third_platform_sku WHERE shop_name = ? AND third_platform_sku_id = ?",
                    Long.class,
                    shopName, skuId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}
