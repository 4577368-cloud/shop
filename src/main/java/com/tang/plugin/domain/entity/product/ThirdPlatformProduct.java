package com.tang.plugin.domain.entity.product;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class ThirdPlatformProduct {
    private Long id;
    private String shopName;
    private String shopType;
    private String thirdPlatformItemId;
    private String title;
    private String description;
    private String currency;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private BigDecimal minPriceLocal;
    private BigDecimal maxPriceLocal;
    private Double minWeightGrams;
    private Double maxWeightGrams;
    private Integer delFlag;
    private List<ProductAttribute> productAttributeList = new ArrayList<>();
}
