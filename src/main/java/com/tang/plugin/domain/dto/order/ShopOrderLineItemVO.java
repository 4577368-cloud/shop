package com.tang.plugin.domain.dto.order;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class ShopOrderLineItemVO {
    private String lineId;
    private String title;
    private String sku;
    private Integer quantity;
    private String image;
    private String variantId;
    private BigDecimal price;
    private String bindingStatus;
    private String tangbuySkuId;
    private String tangbuyProductId;
}
