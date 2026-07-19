package com.tang.plugin.domain.dto.match.image;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Normalized 1688 image-search result item (A3-1 stateless preview).
 * Field names mirror the reference integration guide's normalized shape.
 * {@code price} is kept as the raw gateway string (e.g. "12.00"); {@code detailUrl}
 * is normalized by the backend to a directly-openable full 1688 offer link.
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
    private Double similarityScore;
    private Long minOrderQty;
    private Long inventory;
    private String skuId;
}
