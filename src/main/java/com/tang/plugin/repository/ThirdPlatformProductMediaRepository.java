package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.product.ThirdPlatformProductMedia;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * JDBC mirror repository for {@code third_platform_product_media}. Upsert by (shop_name, media_id); soft-delete only.
 */
@Slf4j
@Repository
public class ThirdPlatformProductMediaRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * Soft-delete all media of a product so a re-sync can reactivate only current ones.
     */
    public int softDeleteByItem(String shopName, String itemId) {
        if (StringUtils.isAnyBlank(shopName, itemId)) {
            return 0;
        }
        return jdbcTemplate.update(
                "UPDATE third_platform_product_media SET del_flag = 1 WHERE shop_name = ? AND third_platform_item_id = ?",
                shopName, itemId);
    }

    /**
     * Active media rows for a product, ordered by position.
     */
    public List<ThirdPlatformProductMedia> listByItem(String shopName, String itemId) {
        if (StringUtils.isAnyBlank(shopName, itemId)) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT id, shop_name, shop_type, third_platform_item_id, media_id, url, alt, position, del_flag
                FROM third_platform_product_media
                WHERE shop_name = ? AND third_platform_item_id = ? AND del_flag = 0
                ORDER BY position ASC NULLS LAST, id ASC
                """,
                (rs, rowNum) -> new ThirdPlatformProductMedia()
                        .setId(rs.getLong("id"))
                        .setShopName(rs.getString("shop_name"))
                        .setShopType(rs.getString("shop_type"))
                        .setThirdPlatformItemId(rs.getString("third_platform_item_id"))
                        .setMediaId(rs.getString("media_id"))
                        .setUrl(rs.getString("url"))
                        .setAlt(rs.getString("alt"))
                        .setPosition((Integer) rs.getObject("position"))
                        .setDelFlag(rs.getInt("del_flag")),
                shopName,
                itemId);
    }

    /**
     * Upsert media mirror keyed by (shop_name, media_id); sets del_flag = 0.
     */
    public void upsert(ThirdPlatformProductMedia media) {
        Long id = findId(media.getShopName(), media.getMediaId());
        if (id != null) {
            jdbcTemplate.update(
                    """
                    UPDATE third_platform_product_media
                    SET shop_type = ?, third_platform_item_id = ?, url = ?, alt = ?, position = ?, del_flag = 0
                    WHERE id = ?
                    """,
                    media.getShopType(),
                    media.getThirdPlatformItemId(),
                    media.getUrl(),
                    media.getAlt(),
                    media.getPosition(),
                    id);
            media.setId(id);
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO third_platform_product_media
                (shop_name, shop_type, third_platform_item_id, media_id, url, alt, position, del_flag)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                """,
                media.getShopName(),
                media.getShopType(),
                media.getThirdPlatformItemId(),
                media.getMediaId(),
                media.getUrl(),
                media.getAlt(),
                media.getPosition());
    }

    private Long findId(String shopName, String mediaId) {
        if (StringUtils.isAnyBlank(shopName, mediaId)) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM third_platform_product_media WHERE shop_name = ? AND media_id = ?",
                    Long.class,
                    shopName, mediaId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}
