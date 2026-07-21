package com.tang.plugin.domain.dto.skualign;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class SkuAlignRunStatusVO {
    private Long runId;
    private String runStatus;
    private Integer matchedCount;
    private Integer suggestedCount;
    private Integer unmappedCount;
    private Integer noSourceCount;
    private Integer blockedCount;
    private Integer failedCount;
    private String errorSummary;
    private List<SkuAlignProductSummaryVO> productSummaries;
}
