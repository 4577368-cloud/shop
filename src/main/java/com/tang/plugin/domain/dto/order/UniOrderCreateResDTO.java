package com.tang.plugin.domain.dto.order;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Local stub of tang-order UniOrderCreateResDTO (until tang-api-order is on classpath).
 */
@Data
@Accessors(chain = true)
public class UniOrderCreateResDTO {
    private String tradeNo;
    private Instant expireTime;
    private String type;
    private String orderNo;
    private BigDecimal totalAmount;
    /** purchase line id (string) -> itemNo (TI*) */
    private Map<String, String> orderNoMap = new HashMap<>();
}
