package com.tang.plugin.domain.dto.procurement;

import com.tang.plugin.enums.procurement.ProcurementChainAnomaly;
import com.tang.plugin.enums.procurement.ProcurementExecutionStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskDeliveryStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Read-only full-chain view anchored on a procurement task: task snapshot + outbox delivery
 * snapshot + consumer receipts + execution stub snapshot + runtime-derived anomaly codes.
 * Assembled per request; not persisted. execution is an additive downstream layer and may be null
 * (a task without an execution stub); it never alters the receipts / anomaly semantics above it.
 */
@Data
@Accessors(chain = true)
public class ProcurementChainView {
    private Long taskId;
    private String shopName;
    private String shopType;
    private String outerOrderId;
    private String lineId;
    private String tangbuyProductId;
    private String tangbuySkuId;
    private Integer quantity;
    private String currency;
    private BigDecimal unitPrice;

    private ProcurementTaskStatus taskStatus;
    private ProcurementTaskDeliveryStatus deliveryStatus;
    private Integer deliveryAttempts;
    private Instant lastPulledAt;
    private Instant deliveredAt;
    private Instant createdAt;
    private Instant updatedAt;

    private List<ProcurementConsumptionView> receipts;
    private boolean hasAcceptedReceipt;

    private ProcurementExecutionView execution;
    private boolean hasExecution;
    private ProcurementExecutionStatus executionStatus;

    private List<ProcurementChainAnomaly> anomalies;
}
