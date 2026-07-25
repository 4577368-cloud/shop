package com.tang.plugin.domain.dto.logistics;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.HashMap;
import java.util.Map;

/**
 * 修补已确认决策的线路信息请求。
 *
 * <p>对应前端 {@code patchLogisticsQuotes} 调用。
 * 只能修补**已存在**的 acceptance 记录（按 thirdPlatformSkuId 匹配），
 * 不存在则跳过。不更新 acceptedAt，只覆盖线路三字段 + quote_status。
 *
 * <p>{@code quotes} 的 key = thirdPlatformSkuId。
 */
@Data
@Accessors(chain = true)
public class PatchQuotesRequest {
    private String shopName;
    private Map<String, QuotePayload> quotes = new HashMap<>();

    /**
     * 单条修补载荷。{@code recommendedLine} 必填，否则跳过该 SKU。
     */
    @Data
    @Accessors(chain = true)
    public static class QuotePayload {
        private LogisticsLineVO recommendedLine;
        private java.util.List<LogisticsLineVO> alternativeLines;
        private String quoteStatus;
    }
}
