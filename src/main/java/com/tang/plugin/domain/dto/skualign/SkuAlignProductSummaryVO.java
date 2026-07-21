package com.tang.plugin.domain.dto.skualign;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class SkuAlignProductSummaryVO {
    private String thirdPlatformItemId;
    private String title;
    private String imageUrl;
    private String primaryOfferId;
    private Integer totalVariants;
    private Integer alignedVariants;
    private Integer suggestedVariants;
    private Integer unmappedVariants;
    private Integer noSourceVariants;
    private Integer blockedVariants;
    private Boolean hasMultiSource;
    private String lastAlignmentRunStatus;
    private String lastAlignedAt;
}
