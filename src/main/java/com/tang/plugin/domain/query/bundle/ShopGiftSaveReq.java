package com.tang.plugin.domain.query.bundle;

import lombok.Data;

/**
 * Gift rule on trigger product — written to tangbuy_gift.rule metafield.
 * Phase 1: persist only; checkout free-gift Function is a follow-up.
 */
@Data
public class ShopGiftSaveReq {
    private String shopName;
    /** Trigger product (usually the current card product). */
    private String productId;
    /** qty_gift */
    private String kind;
    private Integer minQty;
    private String giftProductId;
    private String giftVariantId;
    private Integer giftQty;
    private String label;
}
