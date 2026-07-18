package com.tang.plugin.domain.dto.procurement;

import com.tang.plugin.enums.procurement.ProcurementExecutionStatus;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Result of create / complete on the execution stub. A rejected call (task not found / CANCELLED on
 * create / no ACCEPTED receipt / stub not found) surfaces as an exception, never as this result.
 * <p>
 * outcome: CREATED / ALREADY_EXISTS (create) or COMPLETED / ALREADY_COMPLETED (complete).
 */
@Data
@Accessors(chain = true)
public class ProcurementExecutionResult {

    public enum Outcome {
        CREATED,
        ALREADY_EXISTS,
        COMPLETED,
        ALREADY_COMPLETED
    }

    private Long taskId;
    private String shopName;
    private String lineId;
    private String consumerId;
    private Outcome outcome;
    private ProcurementExecutionStatus executionStatus;
}
