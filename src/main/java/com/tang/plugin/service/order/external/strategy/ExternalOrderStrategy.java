package com.tang.plugin.service.order.external.strategy;

import com.tang.plugin.domain.bo.PluginShopBO;
import com.tang.plugin.domain.dto.order.ExternalOrderSyncDTO;
import com.tang.plugin.domain.entity.order.ExternalOrder;
import com.tang.plugin.enums.PluginType;

public interface ExternalOrderStrategy<T extends ExternalOrder> {

    PluginType getPluginType();

    Class<T> getOrderClass();

    void fetchExternalOrderByTimeRange(ExternalOrderSyncDTO syncDTO);

    Long createDraftOrderFromExternal(T externalOrder, PluginShopBO shopBO);
}
