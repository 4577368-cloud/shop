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

    // Shopify 订单行 SKU 图（lineItems.node.image.url，回退 variant.image.url）。仅同步快照用。
    private String imageUrl;

    // --- Binding consumption (P1): populated by OrderBindingResolver, not persisted ---
    private String tangbuyProductId;
    private String tangbuySkuId;
    private OrderLineBindingStatus bindingStatus;
}
