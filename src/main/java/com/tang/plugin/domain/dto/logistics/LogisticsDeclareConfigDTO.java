package com.tang.plugin.domain.dto.logistics;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * Customs / tax preferences for the shop logistics template.
 * Aligned with tang-plugin {@code LogisticsTemplateConfigVO} queryForm fields.
 */
@Data
@Accessors(chain = true)
public class LogisticsDeclareConfigDTO {
    /**
     * 0 = fuzzy declaration, 1 = self declaration.
     */
    private Integer declareMode = 0;
    /**
     * Tax registration mode (subset of tang-plugin registrationType):
     * 0 = tax-exempt / self-pay, 3 = platform IOSS, 4 = personal IOSS.
     */
    private Integer registrationType = 0;
    /** Declared currency code; default USD. */
    private String declareCurrency = "USD";
    /**
     * Absolute package declared value in {@link #declareCurrency}.
     * When null and declareMode=0, clients may derive from goods × {@link #fuzzyRatio}.
     */
    private BigDecimal tax;
    /**
     * Fuzzy declaration ratio as percent of goods value (min 40). Used when tax is null.
     */
    private Integer fuzzyRatio = 40;
    /** IOSS / VAT tax number — required when registrationType=4 (personal IOSS). */
    private String taxNo;
}
