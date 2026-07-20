package com.tang.plugin.domain.dto.catalog;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * Lightweight read-only projection returned by GET /api/plugin/catalog/recommendations.
 * Deliberately separate from {@link TangbuyCatalogProduct} so the API surface stays controlled.
 * {@code price}/{@code currency} are the raw procurement price and its currency (source, e.g. CNY).
 * {@code estimatedSalePrice}/{@code targetCurrency} are derived by the pricing template (M1-2);
 * estimatedSalePrice is a raw number (no formatting/symbol) and is null when price is unknown.
 */
@Data
@Accessors(chain = true)
public class CatalogRecommendationItem {
    private String candidateId;
    private String title;
    private String imageUrl;
    private java.util.List<String> imageUrls;
    private BigDecimal price;
    private String currency;
    private BigDecimal estimatedSalePrice;
    private String targetCurrency;
    private String supplierShop;
    private String skuAttr;
    private String offerId1688;
    private String tangbuyUrl;
    private String upstreamPlatform;
    private String barcode;
}
