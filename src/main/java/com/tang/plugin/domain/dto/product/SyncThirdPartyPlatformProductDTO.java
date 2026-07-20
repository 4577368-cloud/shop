package com.tang.plugin.domain.dto.product;

import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SyncThirdPartyPlatformProductDTO {
    private List<ThirdPlatformProduct> thirdPlatformProductList;
    private List<ThirdPlatformSku> thirdPlatformSkuList;
    /**
     * When true, the Shopify product catalog pull hit the page cap and may be incomplete —
     * full-sync reconcile must be skipped to avoid mass soft-deletes.
     */
    private boolean catalogTruncated;
}
