package com.tang.plugin.domain.dto.logistics;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class LogisticsTemplateVO {
    private String shopName;
    private String packaging;
    private String speedPreference;
    private List<MarketSelectionDTO> markets = new ArrayList<>();
    /** True when no row is stored yet — VO is an in-memory default. */
    private boolean defaultTemplate;
    private String updatedAt;
}
