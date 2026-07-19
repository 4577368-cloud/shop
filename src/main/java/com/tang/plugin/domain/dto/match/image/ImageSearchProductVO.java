package com.tang.plugin.domain.dto.match.image;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Normalized 1688 image-search result item (A3-1 stateless preview).
 * Field names mirror the reference integration guide's normalized shape.
 * {@code price} is kept as the raw gateway string (e.g. "12.00"); {@code detailUrl}
 * is normalized by the backend to a directly-openable full 1688 offer link.
 *
 * <p>Since A3-3b the source is the official cross-border imageQuery, which returns no per-item image
 * similarity: {@code similarityScore} is left null and confidence is conveyed instead by {@code soldCount}
 * (月销) and {@code repurchaseRate} (复购率). Results keep the gateway's relevance order (not re-sorted).
 */
@Data
@Accessors(chain = true)
public class ImageSearchProductVO {
    private String productId;
    private String title;
    private String imageUrl;
    private String detailUrl;
    private String price;
    private String supplier;
    private Long soldCount;
    /** 复购率 display string (e.g. "13%"); the official imageQuery replacement signal for similarity. */
    private String repurchaseRate;
    /** Null since A3-3b (official imageQuery returns no per-item similarity). */
    private Double similarityScore;
    private Long minOrderQty;
    private Long inventory;
    private String skuId;
}
