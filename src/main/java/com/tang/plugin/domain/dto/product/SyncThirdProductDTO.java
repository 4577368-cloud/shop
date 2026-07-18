package com.tang.plugin.domain.dto.product;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Accessors(chain = true)
public class SyncThirdProductDTO {
    private String shopName;
    private String currency;
    private BigDecimal exchangeRate;
    /** Incremental window lower bound (updated_at, UTC). Null means full pull. */
    private Instant updatedAtMin;
}
