package com.tang.plugin.domain.dto.publish;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** One sellable SKU from Tangbuy itemGet, mapped to a Shopify variant. */
@Data
public class PublishVariantSnapshot {
    private String skuId;
    /** Procurement unit price (CNY); sale price is computed server-side. */
    private BigDecimal price;
    private String barcode;
    private String imageUrl;
    private List<PublishOptionValue> optionValues;
}
