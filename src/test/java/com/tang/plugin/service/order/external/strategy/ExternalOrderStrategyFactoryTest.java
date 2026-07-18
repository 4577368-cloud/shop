package com.tang.plugin.service.order.external.strategy;

import com.tang.plugin.enums.PluginType;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
class ExternalOrderStrategyFactoryTest {

    @Resource
    private ExternalOrderStrategyFactory factory;

    @Test
    void shopifyStrategyRegistered() {
        ExternalOrderStrategy<?> strategy = factory.getStrategy(PluginType.SHOPIFY);
        assertNotNull(strategy);
        assertEquals(PluginType.SHOPIFY, strategy.getPluginType());
    }
}
