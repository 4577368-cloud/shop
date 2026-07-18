package com.tang.plugin.service.match.strategy;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.enums.match.MatchSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Registers {@link ProductMatchStrategy} beans by {@link MatchSource}. No hardcoded if-dispatch;
 * RULE / IMAGE / AI just add a bean later.
 */
@Slf4j
@Component
public class ProductMatchStrategyHolder {

    private final Map<MatchSource, ProductMatchStrategy> strategies = new EnumMap<>(MatchSource.class);

    @Resource
    private ObjectProvider<ProductMatchStrategy> strategyProvider;

    @PostConstruct
    public void init() {
        strategyProvider.orderedStream().forEach(strategy -> {
            strategies.put(strategy.support(), strategy);
            log.info("ProductMatchStrategy registered source={}", strategy.support());
        });
    }

    public ProductMatchStrategy get(MatchSource source) {
        ProductMatchStrategy strategy = strategies.get(source);
        if (strategy == null) {
            throw new CustomException("No ProductMatchStrategy for source: " + source);
        }
        return strategy;
    }

    public Set<MatchSource> registeredSources() {
        return strategies.keySet();
    }
}
