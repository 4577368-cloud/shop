package com.tang.plugin.domain.bo.product;

import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter → Handler carrier for one Shopify product.
 * Media travels inside {@link ThirdPlatformProduct#getMediaList()}.
 */
@Data
@Accessors(chain = true)
public class ShopifyProductMirror {
    private ThirdPlatformProduct product;
    private List<ThirdPlatformSku> skuList = new ArrayList<>();
}
