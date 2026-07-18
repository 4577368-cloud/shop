package com.tang.plugin.domain.entity.order;

import com.tang.plugin.enums.order.OrderLineBindingStatus;
import com.tang.plugin.enums.order.OrderLineHandlingStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Persisted order line with binding snapshot. Anchor key (shop_name, line_id).
 * Business fields are a sync-time snapshot; binding fields come from OrderBindingResolver.
 */
@Data
@Accessors(chain = true)
public class ThirdPlatformOrderLine {
    private Long id;
    private String shopName;
    private String shopType;
    private String outerOrderId;
    private String lineId;
    private String outerVariantId;
    private String sku;
    private String title;
    private Integer quantity;
    private BigDecimal price;
    private String tangbuyProductId;
    private String tangbuySkuId;
    private OrderLineBindingStatus bindingStatus;
    /** Operator handling state for UNBOUND lines; null means PENDING. Never written by persist. */
    private OrderLineHandlingStatus handlingStatus;
    private String handlingNote;
    private Instant handledAt;
    /** Informational only — skeleton in-memory draft id (volatile). */
    private Long draftOrderId;
    private Integer delFlag;
    private Instant createdAt;
    private Instant updatedAt;
}
