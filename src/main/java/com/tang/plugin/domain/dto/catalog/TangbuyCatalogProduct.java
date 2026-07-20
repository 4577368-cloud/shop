package com.tang.plugin.domain.dto.catalog;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

/**
 * In-memory model of one Tangbuy offline catalog entry (mirrors test-products.json).
 * {@code price} is the procurement unit price (not a sale price); pricing lands in M1-2.
 */
@Data
@Accessors(chain = true)
public class TangbuyCatalogProduct {
    /** Stable identifier resolved at load time (see TangbuyCatalogService#resolveCandidateId). */
    private String candidateId;
    private String tangbuyProductId;
    private String title;
    private BigDecimal price;
    private String currency;
    private String imageUrl;
    /** Product gallery URLs (Tangbuy {@code itemImages}); first entry mirrors {@link #imageUrl}. */
    private List<String> imageUrls;
    private String tangbuyUrl;
    private String url1688;
    private String offerId1688;
    private String skuId;
    private String frontSkuId;
    private String skuAttr;
    private String supplierShop;
    private String upstreamPlatform;
    private String barcode;
}
