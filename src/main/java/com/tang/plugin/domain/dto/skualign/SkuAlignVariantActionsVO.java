package com.tang.plugin.domain.dto.skualign;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class SkuAlignVariantActionsVO {
    private Boolean canConfirm;
    private Boolean canReselect;
    private Boolean canAddSupplementSource;
    private Boolean canBlock;
}
