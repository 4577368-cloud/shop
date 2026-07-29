package com.tang.plugin.domain.vo.order;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class DraftOrderPurchaseAmountVO {
    private BigDecimal goodsAmountCny;
    private BigDecimal packageAmountCny;
    private BigDecimal totalCny;
}
