package com.tang.plugin.domain.dto.match.sku;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * S1-b0: normalized 1688 cross-border offer detail (subset), centered on the SKU matrix needed for
 * S1-b1 variant↔SKU auto-alignment. Read-only preview; no persistence in this step.
 */
@Data
@Accessors(chain = true)
public class OfferDetailVO {
    private String offerId;
    private String subject;
    private String subjectTrans;
    private String whiteImageUrl;
    private Integer minOrderQuantity;
    private List<OfferSkuVO> skus;
}
