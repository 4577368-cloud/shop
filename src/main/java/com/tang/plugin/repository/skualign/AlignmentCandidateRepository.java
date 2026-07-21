package com.tang.plugin.repository.skualign;

import com.tang.plugin.enums.skualign.ConfidenceLevel;
import com.tang.plugin.enums.skualign.SourceRole;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class AlignmentCandidateRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    public void deleteByRunAndVariant(long runId, String variantId) {
        jdbcTemplate.update(
                "DELETE FROM alignment_candidate WHERE run_id = ? AND third_platform_sku_id = ?",
                runId, variantId);
    }

    public void insert(long runId,
                       String variantId,
                       int rank,
                       String offerId,
                       String offerSkuId,
                       String candidateSource,
                       BigDecimal score,
                       ConfidenceLevel confidenceLevel,
                       String reasonText,
                       boolean selectedBySystem) {
        jdbcTemplate.update(
                """
                INSERT INTO alignment_candidate (
                  run_id, third_platform_sku_id, rank_no, offer_id, offer_sku_id,
                  source_role, candidate_source, score, confidence_level, reason_text,
                  selected_by_system, selected_by_user, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """,
                runId,
                variantId,
                rank,
                offerId,
                offerSkuId,
                SourceRole.PRIMARY.name(),
                candidateSource,
                score,
                confidenceLevel != null ? confidenceLevel.name() : null,
                reasonText,
                selectedBySystem ? 1 : 0,
                Timestamp.from(Instant.now()));
    }
}
