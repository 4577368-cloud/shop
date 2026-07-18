package com.tang.plugin.domain.dto.pricing;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * Read-only view of a shop's effective pricing template. {@code isDefault = true} means no stored
 * template exists yet and the system default is being returned.
 */
@Data
@Accessors(chain = true)
public class PricingTemplateVO {
    private String shopName;
    private String sourceCurrency;
    private String targetCurrency;
    private Double exchangeRate;
    private Double multiplier;
    private Double addend;
    private String roundingStrategy;
    private Integer decimals;
    /** Kept as JSON key "isDefault"; Jackson would otherwise drop the "is" prefix to "default". */
    @JsonProperty("isDefault")
    private boolean isDefault;
    private Instant updatedAt;
}
