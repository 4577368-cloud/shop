package com.tang.plugin.service.publish.component;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Slf4j
@Component
public class ExchangeRateComponent {

    @Value("${tang.plugin.exchange-rate.default:1.0}")
    private BigDecimal defaultRate;

    /**
     * Skeleton: returns configured default rate. Wire real FX service later.
     */
    public BigDecimal getExchangeRate(String currency) {
        log.info("getExchangeRate currency={} rate={}", currency, defaultRate);
        return defaultRate;
    }
}
