package com.tang.plugin.domain.dto.procurement;

import com.tang.plugin.enums.procurement.ProcurementConsumptionStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskDeliveryStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * Consumption receipt joined with the task's current taskStatus / deliveryStatus snapshot,
 * for reconciliation between the consumer ledger and the outbox delivery fact.
 */
@Data
@Accessors(chain = true)
public class ProcurementConsumptionView {
    private Long id;
    private String shopName;
    private Long taskId;
    private String lineId;
    private String consumerId;
    private String consumerRef;
    private ProcurementConsumptionStatus consumptionStatus;
    private Instant receivedAt;
    private Instant acceptedAt;
    private ProcurementTaskStatus taskStatus;
    private ProcurementTaskDeliveryStatus deliveryStatus;
}
