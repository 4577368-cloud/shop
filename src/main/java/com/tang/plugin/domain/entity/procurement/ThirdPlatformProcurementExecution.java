package com.tang.plugin.domain.entity.procurement;

import com.tang.plugin.enums.procurement.ProcurementExecutionStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * Minimal execution placeholder for an accepted procurement task (NOT a real procurement order).
 * One execution per task (idempotent by task_id). completed_at is written once.
 * execution_status is orthogonal to task/delivery/consumption status and never writes back to them.
 */
@Data
@Accessors(chain = true)
public class ThirdPlatformProcurementExecution {
    private Long id;
    private String shopName;
    private Long taskId;
    private String lineId;
    private String consumerId;
    private ProcurementExecutionStatus executionStatus;
    private String note;
    private Instant createdAt;
    private Instant completedAt;
    private Integer delFlag;
    private Instant updatedAt;
}
