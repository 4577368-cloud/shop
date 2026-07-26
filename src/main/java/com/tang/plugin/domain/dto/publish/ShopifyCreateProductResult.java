package com.tang.plugin.domain.dto.publish;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Internal result of a successful Shopify productSet create. The component only returns this when
 * all four GIDs are present (strict success); otherwise it throws, so callers may treat a returned
 * instance as fully populated.
 */
@Data
@Accessors(chain = true)
public class ShopifyCreateProductResult {
    private String productId;
    private String handle;
    private String variantId;
    private String inventoryItemId;
    /** All variant GIDs returned by productSet (same order as create input). */
    private List<String> variantIds = new ArrayList<>();

    public List<String> variantIdsOrEmpty() {
        return variantIds == null ? Collections.emptyList() : variantIds;
    }
}
