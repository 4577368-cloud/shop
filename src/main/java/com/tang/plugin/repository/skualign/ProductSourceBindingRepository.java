package com.tang.plugin.repository.skualign;

import com.tang.plugin.domain.entity.skualign.ProductSourceBinding;
import com.tang.plugin.enums.skualign.ProductOrigin;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class ProductSourceBindingRepository {

    private static final RowMapper<ProductSourceBinding> ROW_MAPPER = (rs, rowNum) ->
            new ProductSourceBinding()
                    .setId(rs.getLong("id"))
                    .setShopName(rs.getString("shop_name"))
                    .setThirdPlatformItemId(rs.getString("third_platform_item_id"))
                    .setPrimaryOfferId(rs.getString("primary_offer_id"))
                    .setPrimarySourceType(rs.getString("primary_source_type"))
                    .setStatus(rs.getString("status"))
                    .setSupplementalOfferIdsJson(rs.getString("supplemental_offer_ids_json"))
                    .setProductOrigin(parseOrigin(rs.getString("product_origin")))
                    .setMatchedVariantsCount(rs.getInt("matched_variants_count"))
                    .setTotalVariantsCount(rs.getInt("total_variants_count"))
                    .setUnresolvedVariantsCount(rs.getInt("unresolved_variants_count"))
                    .setLastAlignmentRunId((Long) rs.getObject("last_alignment_run_id"))
                    .setLastAlignedAt(toInstant(rs.getTimestamp("last_aligned_at")))
                    .setDelFlag(rs.getInt("del_flag"))
                    .setCreatedAt(toInstant(rs.getTimestamp("created_at")))
                    .setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));

    @Resource
    private JdbcTemplate jdbcTemplate;

    public Optional<ProductSourceBinding> findByProduct(String shopName, String productId) {
        if (StringUtils.isAnyBlank(shopName, productId)) {
            return Optional.empty();
        }
        try {
            ProductSourceBinding row = jdbcTemplate.queryForObject(
                    """
                    SELECT * FROM product_source_binding
                    WHERE shop_name = ? AND third_platform_item_id = ? AND del_flag = 0
                    """,
                    ROW_MAPPER, shopName, productId);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void upsertSummary(ProductSourceBinding row) {
        Instant now = Instant.now();
        Optional<ProductSourceBinding> existing = findByProduct(row.getShopName(), row.getThirdPlatformItemId());
        if (existing.isPresent()) {
            jdbcTemplate.update(
                    """
                    UPDATE product_source_binding SET
                      primary_offer_id = ?, primary_source_type = ?, status = ?,
                      matched_variants_count = ?, total_variants_count = ?,
                      unresolved_variants_count = ?, last_alignment_run_id = ?,
                      last_aligned_at = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    row.getPrimaryOfferId(),
                    row.getPrimarySourceType() != null ? row.getPrimarySourceType() : "IMAGE",
                    row.getStatus() != null ? row.getStatus() : "ACTIVE",
                    row.getMatchedVariantsCount() != null ? row.getMatchedVariantsCount() : 0,
                    row.getTotalVariantsCount() != null ? row.getTotalVariantsCount() : 0,
                    row.getUnresolvedVariantsCount() != null ? row.getUnresolvedVariantsCount() : 0,
                    row.getLastAlignmentRunId(),
                    row.getLastAlignedAt() != null ? Timestamp.from(row.getLastAlignedAt()) : Timestamp.from(now),
                    Timestamp.from(now),
                    existing.get().getId());
            return;
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    """
                    INSERT INTO product_source_binding (
                      shop_name, third_platform_item_id, primary_offer_id, primary_source_type,
                      status, product_origin, matched_variants_count, total_variants_count,
                      unresolved_variants_count, last_alignment_run_id, last_aligned_at,
                      del_flag, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """,
                    Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, row.getShopName());
            ps.setString(i++, row.getThirdPlatformItemId());
            ps.setString(i++, row.getPrimaryOfferId());
            ps.setString(i++, row.getPrimarySourceType() != null ? row.getPrimarySourceType() : "IMAGE");
            ps.setString(i++, row.getStatus() != null ? row.getStatus() : "ACTIVE");
            ps.setString(i++, row.getProductOrigin() != null ? row.getProductOrigin().name() : ProductOrigin.EXTERNAL.name());
            ps.setInt(i++, row.getMatchedVariantsCount() != null ? row.getMatchedVariantsCount() : 0);
            ps.setInt(i++, row.getTotalVariantsCount() != null ? row.getTotalVariantsCount() : 0);
            ps.setInt(i++, row.getUnresolvedVariantsCount() != null ? row.getUnresolvedVariantsCount() : 0);
            ps.setObject(i++, row.getLastAlignmentRunId());
            ps.setTimestamp(i++, row.getLastAlignedAt() != null ? Timestamp.from(row.getLastAlignedAt()) : Timestamp.from(now));
            ps.setTimestamp(i++, Timestamp.from(now));
            ps.setTimestamp(i, Timestamp.from(now));
            return ps;
        }, keyHolder);
    }

    /** Register the single V1 supplement offer for a product (at most one). */
    public void setSupplementOffer(String shopName,
                                   String productId,
                                   String primaryOfferId,
                                   String primarySourceType,
                                   ProductOrigin productOrigin,
                                   String supplementOfferIdsJson) {
        Instant now = Instant.now();
        Optional<ProductSourceBinding> existing = findByProduct(shopName, productId);
        if (existing.isPresent()) {
            jdbcTemplate.update(
                    """
                    UPDATE product_source_binding SET
                      supplemental_offer_ids_json = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    supplementOfferIdsJson,
                    Timestamp.from(now),
                    existing.get().getId());
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO product_source_binding (
                  shop_name, third_platform_item_id, primary_offer_id, primary_source_type,
                  status, supplemental_offer_ids_json, product_origin,
                  matched_variants_count, total_variants_count, unresolved_variants_count,
                  del_flag, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, 0, 0, 0, 0, ?, ?)
                """,
                shopName,
                productId,
                primaryOfferId,
                primarySourceType != null ? primarySourceType : "IMAGE",
                supplementOfferIdsJson,
                productOrigin != null ? productOrigin.name() : ProductOrigin.EXTERNAL.name(),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    private static ProductOrigin parseOrigin(String raw) {
        if (StringUtils.isBlank(raw)) {
            return ProductOrigin.EXTERNAL;
        }
        return ProductOrigin.valueOf(raw);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
