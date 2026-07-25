package com.tang.plugin.domain.dto.logistics;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 修补报价响应。{@code patchedCount} = 实际更新的记录数。
 */
@Data
@Accessors(chain = true)
public class PatchQuotesResult {
    private Integer patchedCount;
    private List<LogisticsAcceptanceVO> acceptances = new ArrayList<>();
}
