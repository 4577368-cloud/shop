package com.tang.plugin.domain.dto.procurement;

import com.tang.plugin.enums.procurement.ProcurementChainAnomaly;
import com.tang.plugin.enums.procurement.ProcurementChainAnomaly.Severity;
import com.tang.plugin.enums.procurement.ProcurementTaskDeliveryStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * One actionable anomaly row (task x anomaly code) for the /ops/anomalies list.
 */
@Data
@Accessors(chain = true)
public class ProcurementAnomalyEntry {
    private Long taskId;
    private String shopName;
    private String lineId;
    private ProcurementChainAnomaly anomaly;
    private Severity severity;
    private String description;
    private ProcurementTaskStatus taskStatus;
    private ProcurementTaskDeliveryStatus deliveryStatus;
    private Integer deliveryAttempts;
    private Instant createdAt;
    private Instant lastPulledAt;
    private Instant deliveredAt;
}
