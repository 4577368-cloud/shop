package com.tang.plugin.domain.dto.procurement;

import com.tang.plugin.domain.dto.procurement.ProcurementAckResult.Outcome;
import com.tang.plugin.enums.procurement.ProcurementConsumptionStatus;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Result of a receive / accept. A rejected call (task not found / CANCELLED / soft-deleted)
 * surfaces as an exception, never as this result.
 * <p>
 * consumptionOutcome: RECEIVED / ALREADY_RECEIVED / ACCEPTED / ALREADY_ACCEPTED.
 * deliveryOutcome: only set by accept (the outbox ack outcome); null for receive.
 */
@Data
@Accessors(chain = true)
public class ProcurementConsumeResult {

    public enum ConsumptionOutcome {
        RECEIVED,
        ALREADY_RECEIVED,
        ACCEPTED,
        ALREADY_ACCEPTED
    }

    private Long taskId;
    private String shopName;
    private String lineId;
    private String consumerId;
    private ConsumptionOutcome consumptionOutcome;
    private ProcurementConsumptionStatus consumptionStatus;
    private Outcome deliveryOutcome;
}
