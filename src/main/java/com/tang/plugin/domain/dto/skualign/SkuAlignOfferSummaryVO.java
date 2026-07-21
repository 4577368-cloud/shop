package com.tang.plugin.domain.dto.skualign;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class SkuAlignOfferSummaryVO {
    private String offerId;
    private String detailUrl;
    private String title;
    private String imageUrl;
}
