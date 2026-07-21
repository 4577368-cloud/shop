package com.tang.plugin.domain.dto.publish;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

/** Internal: one Shopify variant ready for productSet (sale price already calculated). */
@Data
@Accessors(chain = true)
public class ShopifyVariantCreateInput {
    private BigDecimal salePrice;
    private String sku;
    private String barcode;
    private List<PublishOptionValue> optionValues;
}
