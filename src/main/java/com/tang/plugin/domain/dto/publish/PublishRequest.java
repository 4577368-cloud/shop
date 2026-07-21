package com.tang.plugin.domain.dto.publish;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** Request body for POST /api/plugin/catalog/publish. Single candidate, no batch. */
@Data
public class PublishRequest {
    private String shopName;
    private String candidateId;

    /**
     * Optional snapshot from the browser when the backend cannot reach tangbuy.cc (Render). When
     * {@code title} is present, {@link com.tang.plugin.service.publish.CatalogPublishService}
     * skips the live mall lookup.
     */
    private String title;
    private BigDecimal price;
    private String currency;
    private String imageUrl;
    /** Full gallery from Tangbuy list {@code itemImages}; preferred over {@link #imageUrl}. */
    private List<String> imageUrls;
    private String tangbuyUrl;
    private String supplierShop;
    private String upstreamPlatform;
    private String skuAttr;
    private String barcode;
    /** Rich HTML from browser itemGet (Tangbuy product detail). */
    private String descriptionHtml;
    private String offerId1688;
    /** Tangbuy SKUs → Shopify variants (from itemGet productSkus). */
    private List<PublishVariantSnapshot> variants;
}
