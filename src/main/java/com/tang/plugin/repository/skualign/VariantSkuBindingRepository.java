package com.tang.plugin.repository.skualign;

import com.tang.plugin.domain.entity.skualign.VariantSkuBinding;
import com.tang.plugin.enums.match.MatchSource;
import com.tang.plugin.enums.skualign.ConfidenceLevel;
import com.tang.plugin.enums.skualign.SourceRole;
import com.tang.plugin.enums.skualign.VariantBindingState;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import com.tang.plugin.utils.GeneratedKeySupport;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Repository
public class VariantSkuBindingRepository {

    private static final RowMapper<VariantSkuBinding> ROW_MAPPER = (rs, rowNum) ->
            new VariantSkuBinding()
                    .setId(rs.getLong("id"))
                    .setShopName(rs.getString("shop_name"))
                    .setThirdPlatformItemId(rs.getString("third_platform_item_id"))
                    .setThirdPlatformSkuId(rs.getString("third_platform_sku_id"))
                    .setOfferId(rs.getString("offer_id"))
                    .setOfferSkuId(rs.getString("offer_sku_id"))
                    .setSourceRole(parseSourceRole(rs.getString("source_role")))
                    .setMatchSource(parseMatchSource(rs.getString("match_source")))
                    .setBindingState(VariantBindingState.valueOf(rs.getString("binding_state")))
                    .setConfidenceScore(rs.getBigDecimal("confidence_score"))
                    .setConfidenceLevel(parseConfidence(rs.getString("confidence_level")))
                    .setExplanationJson(rs.getString("explanation_json"))
                    .setManualLocked(rs.getInt("is_manual_locked") == 1)
                    .setActive(rs.getInt("active") == 1)
                    .setCreatedByType(rs.getString("created_by_type"))
                    .setCreatedById(rs.getString("created_by_id"))
                    .setDelFlag(rs.getInt("del_flag"))
                    .setCreatedAt(toInstant(rs.getTimestamp("created_at")))
                    .setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));

    @Resource
    private JdbcTemplate jdbcTemplate;

    public Optional<VariantSkuBinding> findActiveByVariant(String shopName, String variantId) {
        if (StringUtils.isAnyBlank(shopName, variantId)) {
            return Optional.empty();
        }
        try {
            VariantSkuBinding row = jdbcTemplate.queryForObject(
                    """
                    SELECT * FROM variant_sku_binding
                    WHERE shop_name = ? AND third_platform_sku_id = ? AND active = 1 AND del_flag = 0
                    """,
                    ROW_MAPPER, shopName, variantId);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Map<String, VariantSkuBinding> mapActiveByProduct(String shopName, String productId) {
        Map<String, VariantSkuBinding> out = new HashMap<>();
        if (StringUtils.isAnyBlank(shopName, productId)) {
            return out;
        }
        List<VariantSkuBinding> rows = jdbcTemplate.query(
                """
                SELECT * FROM variant_sku_binding
                WHERE shop_name = ? AND third_platform_item_id = ? AND active = 1 AND del_flag = 0
                """,
                ROW_MAPPER, shopName, productId);
        for (VariantSkuBinding row : rows) {
            out.put(row.getThirdPlatformSkuId(), row);
        }
        return out;
    }

    public void deactivateByVariant(String shopName, String variantId) {
        jdbcTemplate.update(
                """
                UPDATE variant_sku_binding SET active = 0, updated_at = ?
                WHERE shop_name = ? AND third_platform_sku_id = ? AND active = 1 AND del_flag = 0
                """,
                Timestamp.from(Instant.now()), shopName, variantId);
    }

    public void upsertActive(VariantSkuBinding binding) {
        deactivateByVariant(binding.getShopName(), binding.getThirdPlatformSkuId());
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    """
                    INSERT INTO variant_sku_binding (
                      shop_name, third_platform_item_id, third_platform_sku_id,
                      offer_id, offer_sku_id, source_role, match_source, binding_state,
                      confidence_score, confidence_level, explanation_json,
                      is_manual_locked, active, created_by_type, created_by_id,
                      del_flag, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 0, ?, ?)
                    """,
                    new String[] { "id" });
            int i = 1;
            ps.setString(i++, binding.getShopName());
            ps.setString(i++, binding.getThirdPlatformItemId());
            ps.setString(i++, binding.getThirdPlatformSkuId());
            ps.setString(i++, binding.getOfferId());
            ps.setString(i++, binding.getOfferSkuId());
            ps.setString(i++, binding.getSourceRole() != null ? binding.getSourceRole().name() : SourceRole.PRIMARY.name());
            ps.setString(i++, binding.getMatchSource() != null ? binding.getMatchSource().name() : MatchSource.RULE.name());
            ps.setString(i++, binding.getBindingState().name());
            ps.setBigDecimal(i++, binding.getConfidenceScore());
            ps.setString(i++, binding.getConfidenceLevel() != null ? binding.getConfidenceLevel().name() : null);
            ps.setString(i++, binding.getExplanationJson());
            ps.setInt(i++, binding.isManualLocked() ? 1 : 0);
            ps.setString(i++, binding.getCreatedByType() != null ? binding.getCreatedByType() : "SYSTEM");
            ps.setString(i++, binding.getCreatedById());
            ps.setTimestamp(i++, Timestamp.from(now));
            ps.setTimestamp(i, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Long id = GeneratedKeySupport.resolveId(keyHolder);
        if (id != null) {
            binding.setId(id);
        }
    }

    private static SourceRole parseSourceRole(String raw) {
        if (StringUtils.isBlank(raw)) {
            return SourceRole.PRIMARY;
        }
        return SourceRole.valueOf(raw);
    }

    private static MatchSource parseMatchSource(String raw) {
        if (StringUtils.isBlank(raw)) {
            return MatchSource.RULE;
        }
        return MatchSource.valueOf(raw);
    }

    private static ConfidenceLevel parseConfidence(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        return ConfidenceLevel.valueOf(raw);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
