package com.tang.plugin.domain.dto.match.image;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * A3-3a: normalized page of the official 1688 cross-border image search (多语言图搜). {@code imageId}
 * echoes the id used for the query (from {@code product.image.upload}) so the caller can reuse it.
 * An empty {@link #items} is a normal miss, not an error. Read-only preview; no persistence.
 */
@Data
@Accessors(chain = true)
public class OfferImageSearchResultVO {
    private String imageId;
    private Integer totalRecords;
    private Integer totalPage;
    private Integer currentPage;
    private Integer pageSize;
    private List<OfferImageSearchItemVO> items;
}
