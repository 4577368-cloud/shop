package com.tang.plugin.domain.vo.order;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class CreateDraftOrderPurchaseVO {
    private String tradeNo;
    private Instant expireTime;
    private String type;
    private Long orderId;
    private String outerOrderId;
    private String tangbuyOrderNo;
    private BigDecimal payableAmountCny;
    private List<String> lineNos = new ArrayList<>();
}
