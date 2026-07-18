package com.tang.plugin.domain.dto.pricing;

import lombok.Data;

/**
 * Request body for POST /api/plugin/pricing/template. Only {@code shopName} and {@code exchangeRate}
 * are required; every other field falls back to a documented default in PricingTemplateService.
 */
@Data
public class PricingTemplateUpsertRequest {
    private String shopName;
    private Double exchangeRate;
    private Double multiplier;
    private Double addend;
    private String roundingStrategy;
    private Integer decimals;
    private String sourceCurrency;
    private String targetCurrency;
}
