package com.tang.plugin.domain.dto.procurement;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Result of procurement-task creation.
 * matched = order lines meeting the creation preconditions (del_flag=0, BOUND, tangbuySkuId present);
 * created = tasks newly inserted; skippedExisting = matched lines already having a task;
 * skippedNotBound = remaining lines not meeting the preconditions.
 */
@Data
@Accessors(chain = true)
public class ProcurementTaskCreateResult {
    private String shopName;
    private String outerOrderId;
    private int matched;
    private int created;
    private int skippedExisting;
    private int skippedNotBound;
}
