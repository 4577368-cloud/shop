package com.tang.plugin.domain.query.order;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class DraftOrderRefundReq {
    private Long orderId;
    private Long orderLineId;
    private Integer refundNum;
    private BigDecimal refundAmount;
    private String reason;
    private List<Long> orderLineIds;
}
