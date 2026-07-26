package com.tang.plugin.service.match;

import com.tang.plugin.domain.dto.match.image.ImageSearchProductVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MatchCandidateRankerTest {

    private static ImageSearchProductVO item(String id, Double similarity, Long sold, String repurchase) {
        return new ImageSearchProductVO()
                .setProductId(id)
                .setSimilarityScore(similarity)
                .setSoldCount(sold)
                .setRepurchaseRate(repurchase);
    }

    @Test
    void whenSimilarityMissing_preservesGatewayOrder_ignoresSold() {
        List<ImageSearchProductVO> items = List.of(
                item("low-sold", null, 10L, "1%"),
                item("high-sold", null, 999_999L, "99%")
        );
        assertEquals(0, MatchCandidateRanker.pickBestIndex(items));
    }

    @Test
    void whenSimilarityPresent_picksHighestSimilarity_overSold() {
        List<ImageSearchProductVO> items = List.of(
                item("popular", 0.60, 500_000L, "50%"),
                item("lookalike", 0.95, 12L, "2%")
        );
        assertEquals(1, MatchCandidateRanker.pickBestIndex(items));
    }

    @Test
    void whenSimilarityTied_usesSoldThenRepurchase() {
        List<ImageSearchProductVO> items = List.of(
                item("a", 90.0, 100L, "10%"),
                item("b", 90.0, 200L, "5%")
        );
        assertEquals(1, MatchCandidateRanker.pickBestIndex(items));

        List<ImageSearchProductVO> byRepurchase = List.of(
                item("a", 90.0, 100L, "10%"),
                item("b", 90.0, 100L, "40%")
        );
        assertEquals(1, MatchCandidateRanker.pickBestIndex(byRepurchase));
    }

    @Test
    void emptyList_returnsZero() {
        assertEquals(0, MatchCandidateRanker.pickBestIndex(List.of()));
        assertEquals(0, MatchCandidateRanker.pickBestIndex(null));
    }
}
