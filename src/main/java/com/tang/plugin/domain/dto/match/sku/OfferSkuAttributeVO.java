package com.tang.plugin.domain.dto.match.sku;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * S1-b0: one spec attribute of a 1688 SKU (e.g. 颜色/红色), with the English translation and per-value
 * image the cross-border detail API provides. Used later (S1-b1) to align against Shopify options.
 */
@Data
@Accessors(chain = true)
public class OfferSkuAttributeVO {
    private String attributeId;
    private String attributeName;
    private String value;
    private String attributeNameTrans;
    private String valueTrans;
    private String skuImageUrl;
}
