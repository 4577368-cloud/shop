package com.tang.plugin.domain.dto.skualign;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class SkuAlignCurrentBindingVO {
    private String offerId;
    private String offerSkuId;
    private String bindingState;
    private String sourceRole;
    private String matchSource;
    private String confidenceLevel;
    private Boolean manualLocked;
}
