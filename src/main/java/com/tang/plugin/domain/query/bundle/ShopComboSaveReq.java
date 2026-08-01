package com.tang.plugin.domain.query.bundle;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Track B — same-product combo (qty discount or variant pair). Written to product metafield;
 * does not create a Fixed Bundle parent.
 */
@Data
public class ShopComboSaveReq {
    private String shopName;
    private String productId;
    /** qty_discount | variant_pair */
    private String kind;
    private Integer qty;
    private BigDecimal discountPercent;
    private List<String> variantIds;
    private String label;
}
