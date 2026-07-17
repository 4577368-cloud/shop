package com.tang.plugin.domain.dto.order;

import com.tang.plugin.domain.bo.PluginShopBO;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ExternalOrderSyncDTO {
    private PluginShopBO shop;
    /** Sync type placeholder — align with main repo later */
    private Integer type;
    private Long startTime;
    private Long endTime;
}
