package com.tang.plugin.domain.dto.skualign;

import com.tang.plugin.enums.skualign.SourceRole;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class SkuAlignManualBindDTO {
    private String shopName;
    private String thirdPlatformItemId;
    private String thirdPlatformSkuId;
    private String offerId;
    private String offerSkuId;
    private SourceRole sourceRole;
    private String reason;
}
