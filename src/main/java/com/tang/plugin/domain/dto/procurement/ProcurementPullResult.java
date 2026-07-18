package com.tang.plugin.domain.dto.procurement;

import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementTask;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * Result of an outbox pull. Read-only snapshot of deliverable tasks with an observational
 * pulled marker applied (delivery_attempts += 1, last_pulled_at = now). No claim / lease.
 */
@Data
@Accessors(chain = true)
public class ProcurementPullResult {
    private String shopName;
    private int limit;
    private int pulled;
    private List<ThirdPlatformProcurementTask> tasks;
}
