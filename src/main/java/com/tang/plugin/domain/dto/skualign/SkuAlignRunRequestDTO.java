package com.tang.plugin.domain.dto.skualign;

import com.tang.plugin.enums.skualign.AlignmentTriggerType;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class SkuAlignRunRequestDTO {
    private String shopName;
    private AlignmentTriggerType triggerType;
    private String scopeType;
    private List<String> scopeIds;
    private Boolean forceRefresh;
}
