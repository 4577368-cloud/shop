package com.tang.plugin.domain.dto.skualign;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class SkuAlignSuggestedCandidateVO {
    private String offerId;
    private String offerSkuId;
    private String specName;
    private String confidenceLevel;
    private Double score;
}
