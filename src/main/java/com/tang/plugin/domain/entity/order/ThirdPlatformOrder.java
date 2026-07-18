package com.tang.plugin.domain.entity.order;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Minimal persisted order header. Idempotent key (shop_type, shop_name, outer_order_id).
 * Snapshot fields are written once on first ingest; P1 does not refresh on re-sync.
 */
@Data
@Accessors(chain = true)
public class ThirdPlatformOrder {
    private Long id;
    private String shopName;
    private String shopType;
    private String outerOrderId;
    private String orderName;
    private String financialStatus;
    private String fulfillmentStatus;
    private String currency;
    private BigDecimal totalPrice;
    private Instant platformCreatedAt;
    private Instant platformUpdatedAt;
    private Integer delFlag;
    private Instant createdAt;
    private Instant updatedAt;
}
