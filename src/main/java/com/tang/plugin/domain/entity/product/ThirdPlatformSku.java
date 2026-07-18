package com.tang.plugin.domain.entity.product;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class ThirdPlatformSku {
    private Long id;
    private String shopName;
    private String shopType;
    private String thirdPlatformItemId;
    private String thirdPlatformSkuId;
    private String sku;
    private String title;
    private BigDecimal price;
    private BigDecimal priceLocal;
    private Double weightGrams;
    private String option1;
    private String option2;
    private String option3;
    private String imageUrl;
    private String barcode;
    private Integer inventoryQuantity;
    private Integer position;
    private Integer delFlag;
}
