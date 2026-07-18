package com.tang.plugin.domain.entity.order;

import com.tang.plugin.enums.order.OrderLineBindingStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class ExternalOrderLine {
    private String lineId;
    private String sku;
    private String title;
    private String variantTitle;
    private Integer quantity;
    private BigDecimal price;
    private String outerVariantId;

    // --- Binding consumption (P1): populated by OrderBindingResolver, not persisted ---
    private String tangbuyProductId;
    private String tangbuySkuId;
    private OrderLineBindingStatus bindingStatus;
}
