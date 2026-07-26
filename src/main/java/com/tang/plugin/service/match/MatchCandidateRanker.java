package com.tang.plugin.service.match;

import com.tang.plugin.domain.dto.match.image.ImageSearchProductVO;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Rank image-search candidates with <b>similarity always first</b>.
 *
 * <p>Official multilingual image search (A3-3b) does not return per-item similarity scores, so
 * {@code similarityScore} is often null. In that case the gateway recall order <em>is</em> the
 * similarity ranking — commercial signals (sold / repurchase) must not reorder past it.
 *
 * <ol>
 *   <li>When any candidate has a usable similarity score: pick by similarity DESC, then sold,
 *       repurchase, then original index.</li>
 *   <li>When all similarity scores are missing: keep gateway order (index 0 wins).</li>
 * </ol>
 */
public final class MatchCandidateRanker {

    private MatchCandidateRanker() {
    }

    public static int pickBestIndex(List<ImageSearchProductVO> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        if (!anyHasSimilarity(items)) {
            // Trust 1688 relevance order; do not let sold/repurchase steal the top slot.
            return 0;
        }
        int bestIdx = 0;
        for (int i = 1; i < items.size(); i++) {
            if (isBetter(items.get(i), i, items.get(bestIdx), bestIdx) > 0) {
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    /**
     * @return positive if {@code a} is better than {@code b}
     */
    static int isBetter(ImageSearchProductVO a, int indexA, ImageSearchProductVO b, int indexB) {
        int scoreA = similarityOrZero(a);
        int scoreB = similarityOrZero(b);
        if (scoreA != scoreB) {
            return scoreA - scoreB;
        }
        // Real similarity tied (both > 0): commercial signals may break the tie.
        if (scoreA > 0) {
            long soldDiff = sold(a) - sold(b);
            if (soldDiff != 0) {
                return soldDiff > 0 ? 1 : -1;
            }
            double repDiff = parseRepurchase(a.getRepurchaseRate()) - parseRepurchase(b.getRepurchaseRate());
            if (repDiff != 0) {
                return repDiff > 0 ? 1 : -1;
            }
        }
        // Missing / zero similarity, or full commercial tie → preserve gateway order.
        return indexB - indexA;
    }

    private static boolean anyHasSimilarity(List<ImageSearchProductVO> items) {
        for (ImageSearchProductVO item : items) {
            if (normalizeMatchScore(item == null ? null : item.getSimilarityScore()) != null) {
                return true;
            }
        }
        return false;
    }

    private static int similarityOrZero(ImageSearchProductVO c) {
        Integer n = normalizeMatchScore(c == null ? null : c.getSimilarityScore());
        return n == null ? 0 : n;
    }

    private static long sold(ImageSearchProductVO c) {
        return c == null || c.getSoldCount() == null ? 0L : c.getSoldCount();
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
