package com.tang.plugin.domain.dto.product;

import com.tang.plugin.domain.entity.product.ThirdPlatformProductMedia;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only Shopify product mirror detail for the workbench drawer (Phase 1).
 */
@Data
@Accessors(chain = true)
public class ShopProductDetailVO {
    private Long id;
    private String shopName;
    private String thirdPlatformItemId;
    private String handle;
    private String title;
    private String description;
    private String status;
    private String currency;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private Double minWeightGrams;
    private Double maxWeightGrams;
    private String primaryImageUrl;
    private Instant updatedAt;
    private List<ThirdPlatformSku> variants = new ArrayList<>();
    private List<ThirdPlatformProductMedia> media = new ArrayList<>();
}
