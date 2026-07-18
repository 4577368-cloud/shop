package com.tang.plugin.domain.entity.publish;

import com.tang.plugin.enums.publish.ProductPublishStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Publish idempotency ledger row: one Shopify product per (shop_name, candidate_id). Carries a
 * snapshot of what was published (title/prices) and the Shopify GIDs returned on success. See
 * {@link ProductPublishStatus} for the lifecycle. Shopify GID/handle fields are filled by M1-4.
 */
@Data
@Accessors(chain = true)
public class ProductPublishRecord {
    private Long id;
    private String shopName;
    private String shopType;
    private String candidateId;
    private String tangbuyProductId;
    private String offerId1688;
    private String skuId;
    private String title;
    private BigDecimal sourcePrice;
    private String sourceCurrency;
    private BigDecimal salePrice;
    private String targetCurrency;
    private ProductPublishStatus publishStatus;
    private String shopifyProductId;
    private String shopifyProductHandle;
    private String shopifyVariantId;
    private String shopifyInventoryItemId;
    private int attempts;
    private String errorMessage;
    private Instant publishedAt;
    private int delFlag;
    private Instant createdAt;
    private Instant updatedAt;
}
