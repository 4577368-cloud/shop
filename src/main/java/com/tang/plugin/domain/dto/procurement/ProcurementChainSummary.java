package com.tang.plugin.domain.dto.procurement;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

/**
 * Triage overview for a shop. Status counts are exact (SQL GROUP BY over del_flag=0).
 * anomalyCounts are runtime-derived over a bounded scan window (see service scan cap).
 */
@Data
@Accessors(chain = true)
public class ProcurementChainSummary {
    private String shopName;
    private Map<String, Long> taskStatusCounts;
    private Map<String, Long> deliveryStatusCounts;
    private Map<String, Long> consumptionStatusCounts;
    private Map<String, Long> anomalyCounts;
}
