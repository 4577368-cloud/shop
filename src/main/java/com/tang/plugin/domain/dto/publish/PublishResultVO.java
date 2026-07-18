package com.tang.plugin.domain.dto.publish;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * Response of POST /api/plugin/catalog/publish. Carries the resulting publish status and, on
 * success/short-circuit, the Shopify GIDs. Repeat-trigger semantics: PUBLISHED short-circuits with
 * existing ids; PUBLISHING returns in-progress without calling Shopify again.
 */
@Data
@Accessors(chain = true)
public class PublishResultVO {
    private String status;
    private String publishStatus;
    private String candidateId;
    private String shopifyProductId;
    private String shopifyProductHandle;
    private String shopifyVariantId;
    private BigDecimal salePrice;
    private String targetCurrency;
    private String message;
}
