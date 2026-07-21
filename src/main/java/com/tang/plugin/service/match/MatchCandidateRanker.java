package com.tang.plugin.service.match;

import com.tang.plugin.domain.dto.match.image.ImageSearchProductVO;
import org.apache.commons.lang3.StringUtils;

import java.util.Comparator;
import java.util.List;

/**
 * Rank image-search candidates: match score first, then monthly sold, then repurchase rate.
 */
public final class MatchCandidateRanker {

    private MatchCandidateRanker() {
    }

    public static int pickBestIndex(List<ImageSearchProductVO> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int bestIdx = 0;
        double bestRank = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < items.size(); i++) {
            double rank = rank(items.get(i));
            if (rank > bestRank) {
                bestRank = rank;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private static double rank(ImageSearchProductVO c) {
        double match = normalizeMatchScore(c.getSimilarityScore()) / 100.0;
        long sold = c.getSoldCount() == null ? 0L : c.getSoldCount();
        double repurchase = parseRepurchase(c.getRepurchaseRate()) / 100.0;
        // Match-first weighting mirrors frontend pickBestCandidateIndex.
        return match * 0.5 + Math.min(sold / 100_000.0, 1.0) * 0.3 + repurchase * 0.2;
    }

    static Integer normalizeMatchScore(Double score) {
        if (score == null || score.isNaN() || score <= 0) {
            return null;
        }
        if (score <= 1) {
            return (int) Math.round(score * 100);
        }
        return (int) Math.round(Math.min(score, 100));
    }

    private static double parseRepurchase(String raw) {
        if (StringUtils.isBlank(raw)) {
            return 0;
        }
        try {
            return Double.parseDouble(raw.replace("%", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
