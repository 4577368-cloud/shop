package com.tang.plugin.domain.query.order;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class DraftOrderFillReq {
    private Long orderId;
    private Long packageId;
    private BigDecimal amount;
    private Long userId;
}
