package com.tang.plugin.domain.entity.logistics;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

@Data
@Accessors(chain = true)
public class LogisticsTemplate {
    private Long id;
    private String shopName;
    private String packaging;
    /**
     * Legacy DB column — no longer exposed on the API. Kept for backward-compatible rows.
     * Writes always persist BALANCED.
     */
    private String speedPreference;
    /** JSON array of {marketGroupId, countryCodes:[]} */
    private String marketsJson;
    /**
     * JSON object matching LogisticsDeclareConfigDTO
     * (declareMode / registrationType / declareCurrency / tax / fuzzyRatio / taxNo).
     */
    private String declareJson;
    private Integer delFlag;
    private Instant createdAt;
    private Instant updatedAt;
}
