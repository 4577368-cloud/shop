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
    private String speedPreference;
    /** JSON array of {marketGroupId, countryCodes:[]} */
    private String marketsJson;
    private Integer delFlag;
    private Instant createdAt;
    private Instant updatedAt;
}
