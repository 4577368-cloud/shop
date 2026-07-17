package com.tang.plugin.service.order.external.strategy;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.entity.order.ExternalOrder;
import com.tang.plugin.enums.PluginType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Factory — Spring injects all ExternalOrderStrategy beans.
 * NO if (shopType == xxx) branching elsewhere.
 */
@Slf4j
@Component
public class ExternalOrderStrategyFactory {

    @Resource
    private ObjectProvider<ExternalOrderStrategy<?>> strategyProvider;

    private final Map<PluginType, ExternalOrderStrategy<?>> registry = new EnumMap<>(PluginType.class);

    @PostConstruct
    public void init() {
        strategyProvider.orderedStream().forEach(strategy -> {
            registry.put(strategy.getPluginType(), strategy);
            log.info("Registered ExternalOrderStrategy: {}", strategy.getPluginType());
        });
    }

    @SuppressWarnings("unchecked")
    public <T extends ExternalOrder> ExternalOrderStrategy<T> getStrategy(PluginType pluginType) {
        ExternalOrderStrategy<?> strategy = registry.get(pluginType);
        if (strategy == null) {
            throw new CustomException("No ExternalOrderStrategy for type: " + pluginType);
        }
        return (ExternalOrderStrategy<T>) strategy;
    }
}
