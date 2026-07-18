package com.tang.plugin.domain.entity.product;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class ThirdPlatformProduct {
    private Long id;
    private String shopName;
    private String shopType;
    private String thirdPlatformItemId;
    private String handle;
    private String title;
    private String description;
    private String status;
    private String currency;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private BigDecimal minPriceLocal;
    private BigDecimal maxPriceLocal;
    private Double minWeightGrams;
    private Double maxWeightGrams;
    private String primaryImageUrl;
    private Instant updatedAt;
    private Integer delFlag;
    /** In-memory only in P1 (no attribute relation table). */
    private List<ProductAttribute> productAttributeList = new ArrayList<>();
    /** Carried within the product for single-transaction persistence. */
    private List<ThirdPlatformProductMedia> mediaList = new ArrayList<>();
}
