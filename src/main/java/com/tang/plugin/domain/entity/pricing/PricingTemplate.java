package com.tang.plugin.domain.entity.pricing;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * Per-shop pricing template (single active row per shop). Holds the deterministic rule used to
 * derive a sale price from a procurement price. A row with {@code id == null} represents the
 * in-memory system default (not persisted). See PriceCalculator for the exact formula.
 */
@Data
@Accessors(chain = true)
public class PricingTemplate {
    private Long id;
    private String shopName;
    private String sourceCurrency;
    private String targetCurrency;
    /** Target-currency units per 1 source-currency unit (e.g. 1 CNY = 0.14 USD). */
    private Double exchangeRate;
    private Double multiplier;
    private Double addend;
    private String roundingStrategy;
    private Integer decimals;
    private Instant createdAt;
    private Instant updatedAt;
    private int delFlag;
}
