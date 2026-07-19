package com.tang.plugin.domain.dto.match.sku;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * S1-b0: a single 1688 SKU of an offer (normalized from {@code productSkuInfos}). Carries the spec
 * matrix ({@link #skuAttributes}), the various price flavours, and stock.
 */
@Data
@Accessors(chain = true)
public class OfferSkuVO {
    private String skuId;
    private String specId;
    private String cargoNumber;
    /** Wholesale price string as returned by the gateway. */
    private String price;
    /** 代发价 (consignment / dropship price). */
    private String consignPrice;
    /** 分销一件代发价. */
    private String fenxiaoOnePiecePrice;
    /** 分销供货价. */
    private String fenxiaoOfferPrice;
    private String promotionPrice;
    private Integer amountOnSale;
    private List<OfferSkuAttributeVO> skuAttributes;
}
