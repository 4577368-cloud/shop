package com.tang.plugin.repository.skualign;

import com.tang.plugin.domain.dto.skualign.SkuAlignAliasKnowledgeDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Slf4j
@Repository
public class ShopSkuAliasKnowledgeRepository {

    private static final String KNOWLEDGE_TYPE_SPEC_ALIAS = "SPEC_ALIAS";
    private static final String DEFAULT_DERIVED_FROM = "MANUAL_CORRECTION";

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * Upsert a shop-scoped spec alias. Increments {@code evidence_count} when the same
     * (shop, type, source, target) triple already exists.
     */
    public void upsertAlias(SkuAlignAliasKnowledgeDTO dto) {
        if (dto == null || StringUtils.isAnyBlank(dto.getShopName(), dto.getSourceText(), dto.getTargetText())) {
            return;
        }
        String shopName = dto.getShopName().trim();
        String source = dto.getSourceText().trim();
        String target = dto.getTargetText().trim();
        if (source.equalsIgnoreCase(target)) {
            return;
        }
        String category = StringUtils.trimToNull(dto.getCategoryHint());
        String derivedFrom = StringUtils.defaultIfBlank(StringUtils.trimToNull(dto.getDerivedFrom()),
                DEFAULT_DERIVED_FROM);
        Instant now = Instant.now();

        Long existingId = findActiveId(shopName, source, target);
        if (existingId != null) {
            jdbcTemplate.update(
                    """
                    UPDATE shop_sku_alias_knowledge SET
                      category_hint = COALESCE(?, category_hint),
                      derived_from = ?,
                      evidence_count = evidence_count + 1,
                      last_used_at = ?,
                      updated_at = ?
                    WHERE id = ?
                    """,
                    category,
                    derivedFrom,
                    Timestamp.from(now),
                    Timestamp.from(now),
                    existingId);
            log.info("Alias knowledge reinforced shop={} source={} target={} id={}",
                    shopName, source, target, existingId);
            return;
        }

        jdbcTemplate.update(
                """
                INSERT INTO shop_sku_alias_knowledge (
                  shop_name, knowledge_type, source_text, target_text, category_hint,
                  offer_id, weight, status, derived_from, evidence_count,
                  last_used_at, del_flag, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, NULL, 1.0, 'ACTIVE', ?, 1, ?, 0, ?, ?)
                """,
                shopName,
                KNOWLEDGE_TYPE_SPEC_ALIAS,
                truncate(source, 512),
                truncate(target, 512),
                category,
                derivedFrom,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now));
        log.info("Alias knowledge created shop={} source={} target={}", shopName, source, target);
    }

    private Long findActiveId(String shopName, String source, String target) {
        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT id FROM shop_sku_alias_knowledge
                    WHERE shop_name = ? AND knowledge_type = ? AND status = 'ACTIVE'
                      AND del_flag = 0 AND source_text = ? AND target_text = ?
                    LIMIT 1
                    """,
                    Long.class,
                    shopName,
                    KNOWLEDGE_TYPE_SPEC_ALIAS,
                    source,
                    target);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
