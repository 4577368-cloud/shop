package com.tang.plugin.service.order;

import com.tang.plugin.domain.dto.order.ExternalOrderSyncDTO;
import com.tang.plugin.service.order.external.strategy.ExternalOrderStrategy;
import com.tang.plugin.service.order.external.strategy.ExternalOrderStrategyFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ExternalOrderSyncService {

    @Resource
    private ExternalOrderStrategyFactory externalOrderStrategyFactory;

    public void fetchExternalOrderByTimeRange(ExternalOrderSyncDTO syncDTO) {
        if (syncDTO == null || syncDTO.getShop() == null || syncDTO.getShop().getShopType() == null) {
            log.error("fetchExternalOrderByTimeRange invalid dto");
            return;
        }
        ExternalOrderStrategy<?> strategy =
                externalOrderStrategyFactory.getStrategy(syncDTO.getShop().getShopType());
        strategy.fetchExternalOrderByTimeRange(syncDTO);
    }
}
