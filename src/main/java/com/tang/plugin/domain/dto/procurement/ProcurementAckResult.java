package com.tang.plugin.domain.dto.procurement;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Result of an ack. outcome = DELIVERED (transitioned now) or ALREADY_DELIVERED (idempotent no-op).
 * A rejected ack (task not found / CANCELLED) surfaces as an exception, never as this result.
 */
@Data
@Accessors(chain = true)
public class ProcurementAckResult {

    public enum Outcome {
        DELIVERED,
        ALREADY_DELIVERED
    }

    private Long taskId;
    private String shopName;
    private String lineId;
    private Outcome outcome;
}
