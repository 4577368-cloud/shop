package com.tang.plugin.domain.dto.skualign;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class SkuAlignBlockVariantDTO {
    private String shopName;
    private String thirdPlatformItemId;
    private String thirdPlatformSkuId;
    private String reasonCode;
    private String reasonText;
}
