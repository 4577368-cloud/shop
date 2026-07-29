package com.tang.plugin.domain.vo.order;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class DraftOrderFillVO {
    private String payTradeNo;
    private Long feeRecordId;
}
