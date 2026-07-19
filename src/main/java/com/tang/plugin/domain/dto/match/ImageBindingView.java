package com.tang.plugin.domain.dto.match;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * A3-2b read projection of the ACTIVE image-search binding for a shop product (route B, default variant).
 * {@code bound=false} is a normal miss (no binding yet). The resolution context
 * ({@code imageSource}/{@code querySource}/{@code appliedQuery}) and {@code detailUrl} are decoded from
 * the confirmed candidate's structured audit reason. Used both as the confirm response and for回显.
 */
@Data
@Accessors(chain = true)
public class ImageBindingView {
    private boolean bound;
    private String thirdPlatformItemId;
    private String thirdPlatformSkuId;
    private String tangbuyProductId;
    private String tangbuySkuId;
    private BigDecimal matchScore;
    private String imageSource;
    private String querySource;
    private String appliedQuery;
    private String detailUrl;
}
