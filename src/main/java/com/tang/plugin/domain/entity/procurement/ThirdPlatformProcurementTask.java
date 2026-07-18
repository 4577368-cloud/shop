package com.tang.plugin.domain.entity.procurement;

import com.tang.plugin.enums.procurement.ProcurementTaskDeliveryStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Minimal procurement task (outbox / handoff record) generated from a BOUND order line.
 * Not a procurement business entity — no supplier / pricing / fulfillment logic. Idempotent key
 * (shop_name, line_id). Snapshot fields are captured at creation and never refreshed in P1.
 */
@Data
@Accessors(chain = true)
public class ThirdPlatformProcurementTask {
    private Long id;
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
    private Instant deliveredAt;
    private Integer deliveryAttempts;
    private Instant lastPulledAt;
    private Integer delFlag;
    private Instant createdAt;
    private Instant updatedAt;
}
