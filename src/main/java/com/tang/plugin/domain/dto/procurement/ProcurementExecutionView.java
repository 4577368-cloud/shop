package com.tang.plugin.domain.dto.procurement;

import com.tang.plugin.enums.procurement.ProcurementExecutionStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * Read-only slim snapshot of the execution stub for the chain view. Deliberately omits id / del_flag.
 * COMPLETED_STUB is a placeholder marker only and does NOT represent real supplier ordering /
 * procurement execution. execution_status is orthogonal to task/delivery/consumption status.
 */
@Data
@Accessors(chain = true)
public class ProcurementExecutionView {
    private Long taskId;
    private ProcurementExecutionStatus executionStatus;
    private String consumerId;
    private String note;
    private Instant createdAt;
    private Instant completedAt;
}
