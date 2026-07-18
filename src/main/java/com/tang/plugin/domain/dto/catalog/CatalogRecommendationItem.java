package com.tang.plugin.domain.dto.catalog;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * Lightweight read-only projection returned by GET /api/plugin/catalog/recommendations.
 * Deliberately separate from {@link TangbuyCatalogProduct} so the API surface stays controlled.
 * {@code price} is the raw procurement price; no pricing/FX is applied in M1-1.
 */
@Data
@Accessors(chain = true)
public class CatalogRecommendationItem {
    private String candidateId;
    private String title;
    private String imageUrl;
    private BigDecimal price;
    private String currency;
    private String supplierShop;
    private String skuAttr;
    private String offerId1688;
    private String tangbuyUrl;
    private String upstreamPlatform;
    private String barcode;
}
