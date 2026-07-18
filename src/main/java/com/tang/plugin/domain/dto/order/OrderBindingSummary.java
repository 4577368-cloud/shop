package com.tang.plugin.domain.dto.order;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Transient result of order-line binding resolution. For upper-layer logging / debugging only;
 * not persisted and not part of any external (webhook) response.
 */
@Data
@Accessors(chain = true)
public class OrderBindingSummary {
    private String shopName;
    private String orderId;
    private int total;
    private int bound;
    private int unbound;
}
