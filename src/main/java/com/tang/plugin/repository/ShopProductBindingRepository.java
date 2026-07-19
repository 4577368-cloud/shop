package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.match.ShopProductBinding;
import com.tang.plugin.enums.match.BindingStatus;
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
import java.util.Optional;

/**
 * JDBC repository for {@code shop_product_binding}. Anchor key (shop_name, third_platform_sku_id):
 * at most one row per platform SKU; rebind deactivates then rewrites the same row (no physical delete).
 */
@Slf4j
@Repository
public class ShopProductBindingRepository {

    private static final RowMapper<ShopProductBinding> ROW_MAPPER = (rs, rowNum) -> new ShopProductBinding()
            .setId(rs.getLong("id"))
            .setShopName(rs.getString("shop_name"))
            .setShopType(rs.getString("shop_type"))
            .setThirdPlatformItemId(rs.getString("third_platform_item_id"))
            .setThirdPlatformSkuId(rs.getString("third_platform_sku_id"))
            .setTangbuyProductId(rs.getString("tangbuy_product_id"))
            .setTangbuySkuId(rs.getString("tangbuy_sku_id"))
            .setBindSource(rs.getString("bind_source"))
            .setCandidateId((Long) rs.getObject("candidate_id"))
            .setBindStatus(BindingStatus.valueOf(rs.getString("bind_status")))
            .setDelFlag(rs.getInt("del_flag"))
            .setCreatedAt(toInstant(rs.getTimestamp("created_at")))
            .setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));

    private static final String COLUMNS = """
            id, shop_name, shop_type, third_platform_item_id, third_platform_sku_id,
            tangbuy_product_id, tangbuy_sku_id, bind_source, candidate_id, bind_status,
            del_flag, created_at, updated_at
            """;

    @Resource
    private JdbcTemplate jdbcTemplate;

    public Optional<ShopProductBinding> findBySkuId(String shopName, String thirdPlatformSkuId) {
        if (StringUtils.isAnyBlank(shopName, thirdPlatformSkuId)) {
            return Optional.empty();
        }
        try {
            ShopProductBinding binding = jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM shop_product_binding "
                            + "WHERE shop_name = ? AND third_platform_sku_id = ?",
                    ROW_MAPPER, shopName, thirdPlatformSkuId);
            return Optional.ofNullable(binding);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<ShopProductBinding> findActiveBySkuId(String shopName, String thirdPlatformSkuId) {
        return findBySkuId(shopName, thirdPlatformSkuId)
                .filter(b -> b.getDelFlag() != null && b.getDelFlag() == 0)
                .filter(b -> b.getBindStatus() == BindingStatus.ACTIVE);
    }

    /**
     * List all ACTIVE bindings of a shop (del_flag = 0). Read-only; used by A3-2b to回显 bound state
     * for the whole product list in one query.
     */
    public List<ShopProductBinding> listActiveByShop(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM shop_product_binding "
                        + "WHERE shop_name = ? AND bind_status = ? AND del_flag = 0 ORDER BY id DESC",
                ROW_MAPPER, shopName, BindingStatus.ACTIVE.name());
    }

    /**
     * Deactivate the current ACTIVE binding of a SKU (soft). Returns affected rows.
     */
    public int deactivateBySkuId(String shopName, String thirdPlatformSkuId) {
        if (StringUtils.isAnyBlank(shopName, thirdPlatformSkuId)) {
            return 0;
        }
        int rows = jdbcTemplate.update(
                """
                UPDATE shop_product_binding
                SET bind_status = ?, del_flag = 1, updated_at = ?
                WHERE shop_name = ? AND third_platform_sku_id = ? AND bind_status = ?
                """,
                BindingStatus.INACTIVE.name(),
                Timestamp.from(Instant.now()),
                shopName,
                thirdPlatformSkuId,
                BindingStatus.ACTIVE.name());
        log.info("Binding deactivated shopName={} thirdPlatformSkuId={} rows={}",
                shopName, thirdPlatformSkuId, rows);
        return rows;
    }

    /**
     * Write an ACTIVE binding for the SKU. Updates the single anchored row if present, else inserts.
     */
    public void upsertActive(ShopProductBinding binding) {
        Instant now = Instant.now();
        Optional<ShopProductBinding> existing = findBySkuId(binding.getShopName(), binding.getThirdPlatformSkuId());
        if (existing.isPresent()) {
            Long id = existing.get().getId();
            jdbcTemplate.update(
                    """
                    UPDATE shop_product_binding
                    SET shop_type = ?, third_platform_item_id = ?, tangbuy_product_id = ?, tangbuy_sku_id = ?,
                        bind_source = ?, candidate_id = ?, bind_status = ?, del_flag = 0, updated_at = ?
                    WHERE id = ?
                    """,
                    binding.getShopType(),
                    binding.getThirdPlatformItemId(),
                    binding.getTangbuyProductId(),
                    binding.getTangbuySkuId(),
                    binding.getBindSource(),
                    binding.getCandidateId(),
                    BindingStatus.ACTIVE.name(),
                    Timestamp.from(now),
                    id);
            binding.setId(id);
            log.info("Binding activated (update) id={} shopName={} thirdPlatformSkuId={}",
                    id, binding.getShopName(), binding.getThirdPlatformSkuId());
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO shop_product_binding
                (shop_name, shop_type, third_platform_item_id, third_platform_sku_id, tangbuy_product_id,
                 tangbuy_sku_id, bind_source, candidate_id, bind_status, del_flag, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """,
                binding.getShopName(),
                binding.getShopType(),
                binding.getThirdPlatformItemId(),
                binding.getThirdPlatformSkuId(),
                binding.getTangbuyProductId(),
                binding.getTangbuySkuId(),
                binding.getBindSource(),
                binding.getCandidateId(),
                BindingStatus.ACTIVE.name(),
                Timestamp.from(now),
                Timestamp.from(now));
        log.info("Binding activated (insert) shopName={} thirdPlatformSkuId={}",
                binding.getShopName(), binding.getThirdPlatformSkuId());
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
