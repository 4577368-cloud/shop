package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JDBC mirror repository for {@code third_platform_sku}. Upsert by (shop_name, sku_id); soft-delete only.
 */
@Slf4j
@Repository
public class ThirdPlatformSkuRepository {

    private static final RowMapper<ThirdPlatformSku> ROW_MAPPER = (rs, rowNum) -> new ThirdPlatformSku()
            .setId(rs.getLong("id"))
            .setShopName(rs.getString("shop_name"))
            .setShopType(rs.getString("shop_type"))
            .setThirdPlatformItemId(rs.getString("third_platform_item_id"))
            .setThirdPlatformSkuId(rs.getString("third_platform_sku_id"))
            .setSku(rs.getString("sku"))
            .setTitle(rs.getString("title"))
            .setPrice(rs.getBigDecimal("price"))
            .setPriceLocal(rs.getBigDecimal("price_local"))
            .setWeightGrams((Double) rs.getObject("weight_grams"))
            .setOption1(rs.getString("option1"))
            .setOption2(rs.getString("option2"))
            .setOption3(rs.getString("option3"))
            .setImageUrl(rs.getString("image_url"))
            .setBarcode(rs.getString("barcode"))
            .setInventoryQuantity((Integer) rs.getObject("inventory_quantity"))
            .setPosition((Integer) rs.getObject("position"))
            .setDelFlag(rs.getInt("del_flag"));

    private static final String COLUMNS = """
            id, shop_name, shop_type, third_platform_item_id, third_platform_sku_id, sku, title,
            price, price_local, weight_grams, option1, option2, option3, image_url, barcode,
            inventory_quantity, position, del_flag
            """;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * The default (first) active variant of a product: lowest {@code position}, then lowest id.
     * Used by A3-2b route B to resolve the Shopify variant GID for a SKU-level binding without a
     * live Shopify call. Empty when the product has no synced SKU rows (caller should ask for re-sync).
     */
    public Optional<ThirdPlatformSku> findFirstByItem(String shopName, String itemId) {
        if (StringUtils.isAnyBlank(shopName, itemId)) {
            return Optional.empty();
        }
        List<ThirdPlatformSku> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM third_platform_sku "
                        + "WHERE shop_name = ? AND third_platform_item_id = ? AND del_flag = 0 "
                        + "ORDER BY position ASC NULLS LAST, id ASC "
                        + "FETCH FIRST 1 ROW ONLY",
                ROW_MAPPER, shopName, itemId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

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
