package com.tang.plugin.domain.dto.skualign;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class SkuAlignVariantRowVO {
    private String thirdPlatformSkuId;
    private String optionText;
    private String shopifyImage;
    private String salePrice;
    private SkuAlignCurrentBindingVO currentBinding;
    private String reviewState;
    private SkuAlignSuggestedCandidateVO suggestedCandidate;
    private String displaySpecName;
    private String displaySpecImage;
    private String displayProcurementPrice;
    /** READY | LOADING | ERROR */
    private String displayStatus;
    private String displayError;
    private String reasonText;
    private SkuAlignVariantActionsVO actions;
}
