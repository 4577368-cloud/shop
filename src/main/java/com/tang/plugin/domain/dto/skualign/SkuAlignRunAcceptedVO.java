package com.tang.plugin.domain.dto.skualign;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class SkuAlignRunAcceptedVO {
    private Long runId;
    private Boolean accepted;
    private Integer estimatedScopeCount;
}
