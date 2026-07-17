package com.tang.plugin.domain.dto.product;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class SyncThirdProductDTO {
    private String shopName;
    private String currency;
    private BigDecimal exchangeRate;
}
