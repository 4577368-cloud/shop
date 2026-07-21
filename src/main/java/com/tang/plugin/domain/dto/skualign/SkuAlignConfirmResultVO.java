package com.tang.plugin.domain.dto.skualign;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class SkuAlignConfirmResultVO {
    /** Number of variant suggestions promoted to active binding in this request. */
    private Integer confirmedCount;
}
