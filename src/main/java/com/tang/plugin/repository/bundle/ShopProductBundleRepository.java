package com.tang.plugin.repository.bundle;

import com.tang.plugin.domain.entity.bundle.ShopProductBundle;
import com.tang.plugin.enums.bundle.ShopBundleStatus;
import jakarta.annotation.Resource;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
public class ShopProductBundleRepository {

    private static final String COLUMNS = """
            id, shop_name, context_product_id, parent_product_id, parent_variant_id, parent_title,
            parent_price, discount_percent, components_json, status, shopify_operation_id, managed_by_app,
            error_message, synced_at, del_flag, created_at, updated_at
            """;

    private static final RowMapper<ShopProductBundle> ROW_MAPPER = (rs, rowNum) -> new ShopProductBundle()
            .setId(rs.getLong("id"))
            .setShopName(rs.getString("shop_name"))
            .setContextProductId(rs.getString("context_product_id"))
            .setParentProductId(rs.getString("parent_product_id"))
            .setParentVariantId(rs.getString("parent_variant_id"))
            .setParentTitle(rs.getString("parent_title"))
            .setParentPrice(rs.getBigDecimal("parent_price"))
            .setDiscountPercent(rs.getBigDecimal("discount_percent"))
            .setComponentsJson(rs.getString("components_json"))
            .setStatus(ShopBundleStatus.valueOf(rs.getString("status")))
            .setShopifyOperationId(rs.getString("shopify_operation_id"))
            .setManagedByApp(rs.getInt("managed_by_app"))
            .setErrorMessage(rs.getString("error_message"))
            .setSyncedAt(toInstant(rs.getTimestamp("synced_at")))
            .setDelFlag(rs.getInt("del_flag"))
            .setCreatedAt(toInstant(rs.getTimestamp("created_at")))
            .setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));

    @Resource
    private JdbcTemplate jdbcTemplate;

    public Optional<ShopProductBundle> findById(Long id) {
        if (id == null) return Optional.empty();
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM shop_product_bundle WHERE id = ? AND del_flag = 0",
                    ROW_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<ShopProductBundle> listActiveByShop(String shopName) {
        if (StringUtils.isBlank(shopName)) return Collections.emptyList();
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM shop_product_bundle "
                        + "WHERE shop_name = ? AND del_flag = 0 "
                        + "AND status IN ('CREATING','ACTIVE','FAILED','STALE') "
                        + "ORDER BY updated_at DESC",
                ROW_MAPPER, shopName);
    }

    public Optional<ShopProductBundle> findActiveByParentVariant(String shopName, String parentVariantId) {
        if (StringUtils.isAnyBlank(shopName, parentVariantId)) return Optional.empty();
        String id = stripGid(parentVariantId);
        List<ShopProductBundle> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM shop_product_bundle "
                        + "WHERE shop_name = ? AND del_flag = 0 AND status = 'ACTIVE' "
                        + "AND (parent_variant_id = ? OR parent_variant_id = ?) "
                        + "ORDER BY id DESC LIMIT 1",
                ROW_MAPPER, shopName, id, "gid://shopify/ProductVariant/" + id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<ShopProductBundle> findLatestByShopAndContext(String shopName, String contextProductId) {
        if (StringUtils.isAnyBlank(shopName, contextProductId)) return Optional.empty();
        List<ShopProductBundle> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM shop_product_bundle "
                        + "WHERE shop_name = ? AND context_product_id = ? AND del_flag = 0 "
                        + "ORDER BY id DESC LIMIT 1",
                ROW_MAPPER, shopName, contextProductId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public long insert(ShopProductBundle row) {
        Instant now = Instant.now();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO shop_product_bundle ("
                            + "shop_name, context_product_id, parent_product_id, parent_variant_id, parent_title, "
                            + "parent_price, discount_percent, components_json, status, shopify_operation_id, managed_by_app, "
                            + "error_message, synced_at, del_flag, created_at, updated_at"
                            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)",
                    new String[]{"id"});
            int i = 1;
            ps.setString(i++, row.getShopName());
            ps.setString(i++, row.getContextProductId());
            ps.setString(i++, row.getParentProductId());
            ps.setString(i++, row.getParentVariantId());
            ps.setString(i++, row.getParentTitle());
            ps.setBigDecimal(i++, row.getParentPrice());
            ps.setBigDecimal(i++, row.getDiscountPercent());
            ps.setString(i++, row.getComponentsJson());
            ps.setString(i++, row.getStatus().name());
            ps.setString(i++, row.getShopifyOperationId());
            ps.setInt(i++, row.getManagedByApp());
            ps.setString(i++, row.getErrorMessage());
            ps.setTimestamp(i++, toTs(row.getSyncedAt()));
            ps.setTimestamp(i++, toTs(now));
            ps.setTimestamp(i, toTs(now));
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    public void updateAfterPoll(ShopProductBundle row) {
        jdbcTemplate.update(
                "UPDATE shop_product_bundle SET parent_product_id=?, parent_variant_id=?, parent_title=?, "
                        + "parent_price=?, discount_percent=?, components_json=?, status=?, shopify_operation_id=?, "
                        + "error_message=?, synced_at=?, updated_at=? WHERE id=? AND del_flag=0",
                row.getParentProductId(),
                row.getParentVariantId(),
                row.getParentTitle(),
                row.getParentPrice(),
                row.getDiscountPercent(),
                row.getComponentsJson(),
                row.getStatus().name(),
                row.getShopifyOperationId(),
                row.getErrorMessage(),
                toTs(row.getSyncedAt()),
                toTs(Instant.now()),
                row.getId());
    }

    public void markFailed(Long id, String error) {
        jdbcTemplate.update(
                "UPDATE shop_product_bundle SET status=?, error_message=?, updated_at=? "
                        + "WHERE id=? AND del_flag=0",
                ShopBundleStatus.FAILED.name(),
                StringUtils.left(error, 2000),
                toTs(Instant.now()),
                id);
    }

    public List<ShopProductBundle> listByShopTouchingProduct(String shopName, String numericProductId) {
        if (StringUtils.isAnyBlank(shopName, numericProductId)) return Collections.emptyList();
        String id = numericProductId.trim();
        List<ShopProductBundle> out = new ArrayList<>();
        for (ShopProductBundle row : listActiveByShop(shopName)) {
            if (idEquals(row.getParentProductId(), id) || idEquals(row.getContextProductId(), id)) {
                out.add(row);
                continue;
            }
            if (componentsContain(row.getComponentsJson(), id)) {
                out.add(row);
            }
        }
        return out;
    }

    public void updateStatus(Long id, ShopBundleStatus status, String errorMessage) {
        if (id == null || status == null) return;
        jdbcTemplate.update(
                "UPDATE shop_product_bundle SET status=?, error_message=?, updated_at=? "
                        + "WHERE id=? AND del_flag=0",
                status.name(),
                StringUtils.left(errorMessage, 2000),
                toTs(Instant.now()),
                id);
    }

    private static String stripGid(String gidOrId) {
        if (StringUtils.isBlank(gidOrId)) return gidOrId;
        int slash = gidOrId.lastIndexOf('/');
        return slash >= 0 ? gidOrId.substring(slash + 1) : gidOrId.trim();
    }

    private static boolean idEquals(String stored, String numericId) {
        if (StringUtils.isBlank(stored) || StringUtils.isBlank(numericId)) return false;
        return numericId.equals(stripGid(stored));
    }

    private static boolean componentsContain(String componentsJson, String numericId) {
        if (StringUtils.isBlank(componentsJson) || StringUtils.isBlank(numericId)) return false;
        return componentsJson.contains("\"productId\":\"" + numericId + "\"")
                || componentsJson.contains("\"productId\": \"" + numericId + "\"");
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp toTs(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
