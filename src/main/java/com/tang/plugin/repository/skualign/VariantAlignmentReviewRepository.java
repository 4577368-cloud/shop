package com.tang.plugin.repository.skualign;

import com.tang.plugin.domain.entity.skualign.VariantAlignmentReview;
import com.tang.plugin.enums.skualign.ConfidenceLevel;
import com.tang.plugin.enums.skualign.SourceRole;
import com.tang.plugin.enums.skualign.VariantReviewState;
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

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Repository
public class VariantAlignmentReviewRepository {

    private static final RowMapper<VariantAlignmentReview> ROW_MAPPER = (rs, rowNum) ->
            new VariantAlignmentReview()
                    .setId(rs.getLong("id"))
                    .setShopName(rs.getString("shop_name"))
                    .setThirdPlatformItemId(rs.getString("third_platform_item_id"))
                    .setThirdPlatformSkuId(rs.getString("third_platform_sku_id"))
                    .setCurrentOfferScopeJson(rs.getString("current_offer_scope_json"))
                    .setReviewState(VariantReviewState.valueOf(rs.getString("review_state")))
                    .setSuggestedOfferId(rs.getString("suggested_offer_id"))
                    .setSuggestedOfferSkuId(rs.getString("suggested_offer_sku_id"))
                    .setSuggestedSourceRole(parseSourceRole(rs.getString("suggested_source_role")))
                    .setSuggestedMatchSource(rs.getString("suggested_match_source"))
                    .setScore(rs.getBigDecimal("score"))
                    .setConfidenceLevel(parseConfidence(rs.getString("confidence_level")))
                    .setReasonCode(rs.getString("reason_code"))
                    .setReasonText(rs.getString("reason_text"))
                    .setRequiresUserAction(rs.getInt("requires_user_action") == 1)
                    .setLastRunId((Long) rs.getObject("last_run_id"))
                    .setDelFlag(rs.getInt("del_flag"))
                    .setCreatedAt(toInstant(rs.getTimestamp("created_at")))
                    .setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));

    @Resource
    private JdbcTemplate jdbcTemplate;

    public Optional<VariantAlignmentReview> findByVariant(String shopName, String variantId) {
        if (StringUtils.isAnyBlank(shopName, variantId)) {
            return Optional.empty();
        }
        try {
            VariantAlignmentReview row = jdbcTemplate.queryForObject(
                    """
                    SELECT * FROM variant_alignment_review
                    WHERE shop_name = ? AND third_platform_sku_id = ? AND del_flag = 0
                    """,
                    ROW_MAPPER, shopName, variantId);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Map<String, VariantAlignmentReview> mapByProduct(String shopName, String productId) {
        Map<String, VariantAlignmentReview> out = new HashMap<>();
        if (StringUtils.isAnyBlank(shopName, productId)) {
            return out;
        }
        List<VariantAlignmentReview> rows = jdbcTemplate.query(
                """
                SELECT * FROM variant_alignment_review
                WHERE shop_name = ? AND third_platform_item_id = ? AND del_flag = 0
                """,
                ROW_MAPPER, shopName, productId);
        for (VariantAlignmentReview row : rows) {
            out.put(row.getThirdPlatformSkuId(), row);
        }
        return out;
    }

    /** All live reviews for a shop, grouped by product id → variant id. */
    public Map<String, Map<String, VariantAlignmentReview>> mapByShop(String shopName) {
        Map<String, Map<String, VariantAlignmentReview>> out = new HashMap<>();
        if (StringUtils.isBlank(shopName)) {
            return out;
        }
        List<VariantAlignmentReview> rows = jdbcTemplate.query(
                """
                SELECT * FROM variant_alignment_review
                WHERE shop_name = ? AND del_flag = 0
                """,
                ROW_MAPPER, shopName);
        for (VariantAlignmentReview row : rows) {
            out.computeIfAbsent(row.getThirdPlatformItemId(), k -> new HashMap<>())
                    .put(row.getThirdPlatformSkuId(), row);
        }
        return out;
    }

    public void upsert(VariantAlignmentReview review) {
        Instant now = Instant.now();
        Optional<VariantAlignmentReview> existing =
                findByVariant(review.getShopName(), review.getThirdPlatformSkuId());
        if (existing.isPresent()) {
            jdbcTemplate.update(
                    """
                    UPDATE variant_alignment_review SET
                      third_platform_item_id = ?, current_offer_scope_json = ?,
                      review_state = ?, suggested_offer_id = ?, suggested_offer_sku_id = ?,
                      suggested_source_role = ?, suggested_match_source = ?,
                      score = ?, confidence_level = ?, reason_code = ?, reason_text = ?,
                      requires_user_action = ?, last_run_id = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    review.getThirdPlatformItemId(),
                    review.getCurrentOfferScopeJson(),
                    review.getReviewState().name(),
                    review.getSuggestedOfferId(),
                    review.getSuggestedOfferSkuId(),
                    review.getSuggestedSourceRole() != null ? review.getSuggestedSourceRole().name() : null,
                    review.getSuggestedMatchSource(),
                    review.getScore(),
                    review.getConfidenceLevel() != null ? review.getConfidenceLevel().name() : null,
                    review.getReasonCode(),
                    truncate(review.getReasonText(), 1024),
                    review.isRequiresUserAction() ? 1 : 0,
                    review.getLastRunId(),
                    Timestamp.from(now),
                    existing.get().getId());
            return;
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    """
                    INSERT INTO variant_alignment_review (
                      shop_name, third_platform_item_id, third_platform_sku_id,
                      current_offer_scope_json, review_state, suggested_offer_id,
                      suggested_offer_sku_id, suggested_source_role, suggested_match_source,
                      score, confidence_level, reason_code, reason_text,
                      requires_user_action, last_run_id, del_flag, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """,
                    new String[] { "id" });
            int i = 1;
            ps.setString(i++, review.getShopName());
            ps.setString(i++, review.getThirdPlatformItemId());
            ps.setString(i++, review.getThirdPlatformSkuId());
            ps.setString(i++, review.getCurrentOfferScopeJson());
            ps.setString(i++, review.getReviewState().name());
            ps.setString(i++, review.getSuggestedOfferId());
            ps.setString(i++, review.getSuggestedOfferSkuId());
            ps.setString(i++, review.getSuggestedSourceRole() != null ? review.getSuggestedSourceRole().name() : null);
            ps.setString(i++, review.getSuggestedMatchSource());
            ps.setBigDecimal(i++, review.getScore());
            ps.setString(i++, review.getConfidenceLevel() != null ? review.getConfidenceLevel().name() : null);
            ps.setString(i++, review.getReasonCode());
            ps.setString(i++, truncate(review.getReasonText(), 1024));
            ps.setInt(i++, review.isRequiresUserAction() ? 1 : 0);
            ps.setObject(i++, review.getLastRunId());
            ps.setTimestamp(i++, Timestamp.from(now));
            ps.setTimestamp(i, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Long id = GeneratedKeySupport.resolveId(keyHolder);
        if (id != null) {
            review.setId(id);
        }
    }

    private static SourceRole parseSourceRole(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        return SourceRole.valueOf(raw);
    }

    private static ConfidenceLevel parseConfidence(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        return ConfidenceLevel.valueOf(raw);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
