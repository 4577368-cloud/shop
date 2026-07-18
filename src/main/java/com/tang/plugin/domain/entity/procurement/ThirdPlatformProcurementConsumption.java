package com.tang.plugin.domain.entity.procurement;

import com.tang.plugin.enums.procurement.ProcurementConsumptionStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * Consumer integration receipt (consumer-side ledger) for a procurement task.
 * Idempotent by (task_id, consumer_id). received_at / accepted_at are written once and never refreshed.
 */
@Data
@Accessors(chain = true)
public class ThirdPlatformProcurementConsumption {
    private Long id;
    private String shopName;
    private Long taskId;
    private String lineId;
    private String consumerId;
    private String consumerRef;
    private ProcurementConsumptionStatus consumptionStatus;
    private Instant receivedAt;
    private Instant acceptedAt;
    private Integer delFlag;
    private Instant createdAt;
    private Instant updatedAt;
}
