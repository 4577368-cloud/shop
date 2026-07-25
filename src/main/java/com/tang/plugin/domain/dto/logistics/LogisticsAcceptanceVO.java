package com.tang.plugin.domain.dto.logistics;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条物流接受决策视图对象。
 *
 * <p>对应前端 {@code VariantAcceptanceRecord}：
 * {@code thirdPlatformSkuId / thirdPlatformItemId / acceptedAt /
 * recommendedLine / alternativeLines / quoteStatus}。
 */
@Data
@Accessors(chain = true)
public class LogisticsAcceptanceVO {
    private String thirdPlatformSkuId;
    private String thirdPlatformItemId;
    /** ISO-8601 时间戳字符串，与前端 new Date().toISOString() 一致 */
    private String acceptedAt;
    private LogisticsLineVO recommendedLine;
    private List<LogisticsLineVO> alternativeLines = new ArrayList<>();
    private String quoteStatus;
}
