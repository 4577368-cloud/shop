package com.tang.plugin.domain.dto.order;

import com.tang.plugin.enums.order.OrderLineHandlingStatus;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Filter for listing active UNBOUND order lines. Optional handlingStatus / outerOrderId narrow the scope.
 */
@Data
@Accessors(chain = true)
public class UnboundOrderLineQuery {
    private String shopName;
    private OrderLineHandlingStatus handlingStatus;
    private String outerOrderId;
}
