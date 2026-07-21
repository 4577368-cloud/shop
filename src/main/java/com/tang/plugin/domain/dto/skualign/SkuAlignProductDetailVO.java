package com.tang.plugin.domain.dto.skualign;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class SkuAlignProductDetailVO {
    private SkuAlignProductSummaryVO summary;
    private SkuAlignOfferSummaryVO primaryOffer;
    private SkuAlignOfferSummaryVO supplementOffer;
    private List<SkuAlignVariantRowVO> variants;
}
