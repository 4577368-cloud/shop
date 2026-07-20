package com.tang.plugin.domain.dto.logistics;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class CorrectLogisticsTypeRequest {
    private String shopName;
    private String thirdPlatformItemId;
    private String logisticsType;
}
