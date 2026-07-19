package com.tang.plugin.domain.dto.match.image;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * A3-3a: one offer returned by the official 1688 cross-border image search
 * ({@code com.alibaba.fenxiao.crossborder:product.search.imageQuery}, 多语言图搜), normalized.
 * Richer than the Newton {@code find_product} item: carries the multilingual title ({@link #subjectTrans}),
 * price flavours, minimum order quantity, and a directly-openable {@link #detailUrl}.
 */
@Data
@Accessors(chain = true)
public class OfferImageSearchItemVO {
    private String offerId;
    private String subject;
    private String subjectTrans;
    private String imageUrl;
    /** Wholesale price string as returned by the gateway. */
    private String price;
    /** 代发价 (consignment / dropship price). */
    private String consignPrice;
    private String promotionPrice;
    private Integer minOrderQuantity;
    private Integer monthSold;
    private String repurchaseRate;
    /** Supplier company name (gateway {@code companyName}). */
    private String companyName;
    /** Direct, openable offer link (gateway {@code promotionURL}). */
    private String detailUrl;
}
