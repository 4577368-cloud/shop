package com.tang.plugin.service.pricing;

import com.tang.plugin.domain.entity.pricing.PricingTemplate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure unit tests for the deterministic pricing rule. Exchange rate is CNY-per-USD style
 * (converted = cost / rate). Also locks CHARM_99 boundary behavior.
 */
class PriceCalculatorTest {

    private final PriceCalculator calculator = new PriceCalculator();

    private static PricingTemplate template(double rate, double multiplier, double addend,
                                            String rounding, int decimals) {
        return new PricingTemplate()
                .setExchangeRate(rate)
                .setMultiplier(multiplier)
                .setAddend(addend)
                .setRoundingStrategy(rounding)
                .setDecimals(decimals);
    }

    @Test
    void nullCostYieldsNull() {
        assertNull(calculator.calculate(null, template(6.45, 2, 1, "HALF_UP", 2)));
    }

    @Test
    void nullTemplateYieldsNull() {
        assertNull(calculator.calculate(new BigDecimal("14.4"), null));
    }

    @Test
    void nonPositiveRateYieldsNull() {
        assertNull(calculator.calculate(new BigDecimal("10"), template(0, 2, 0, "HALF_UP", 2)));
    }

    @Test
    void halfUpDividesByExchangeRate() {
        // 10 / 6.45 ≈ 1.55038760; * 2 = 3.10077520; + 0.99 = 4.09077520; HALF_UP(2) = 4.09
        BigDecimal sale = calculator.calculate(new BigDecimal("10"), template(6.45, 2, 0.99, "HALF_UP", 2));
        assertEquals(new BigDecimal("4.09"), sale);
    }

    @Test
    void halfUpFollowsFormulaOrder() {
        // 14.4 / 7.2 = 2; * 2 = 4; + 1 = 5; HALF_UP(2) = 5.00
        BigDecimal sale = calculator.calculate(new BigDecimal("14.4"), template(7.2, 2, 1, "HALF_UP", 2));
        assertEquals(new BigDecimal("5.00"), sale);
    }

    @Test
    void halfUpZeroDecimals() {
        // 14.4 / 7.2 * 2 + 1 = 5 -> HALF_UP(0) = 5
        BigDecimal sale = calculator.calculate(new BigDecimal("14.4"), template(7.2, 2, 1, "HALF_UP", 0));
        assertEquals(new BigDecimal("5"), sale);
    }

    @Test
    void ceilRoundsUp() {
        // 10 / 6.45 * 2 + 0 = 3.1007… -> CEIL(2) = 3.11
        BigDecimal sale = calculator.calculate(new BigDecimal("10"), template(6.45, 2, 0, "CEIL", 2));
        assertEquals(new BigDecimal("3.11"), sale);
    }

    @Test
    void floorRoundsDown() {
        // 10 / 6.45 * 2 + 0 = 3.1007… -> FLOOR(2) = 3.10
        BigDecimal sale = calculator.calculate(new BigDecimal("10"), template(6.45, 2, 0, "FLOOR", 2));
        assertEquals(new BigDecimal("3.10"), sale);
    }

    @Test
    void charm99FromFraction() {
        // 10 / 6.45 * 2 + 0 ≈ 3.10 -> ceil int 4 -> 3.99
        BigDecimal sale = calculator.calculate(new BigDecimal("10"), template(6.45, 2, 0, "CHARM_99", 2));
        assertEquals(new BigDecimal("3.99"), sale);
    }

    @Test
    void charm99OnExactIntegerStillDrops() {
        // cost 10 / rate 1 * multiplier 1 + 0 = 10.00 -> ceil 10 -> 9.99
        BigDecimal sale = calculator.calculate(new BigDecimal("10"), template(1, 1, 0, "CHARM_99", 2));
        assertEquals(new BigDecimal("9.99"), sale);
    }

    @Test
    void charm99JustAboveIntegerRoundsUpFirst() {
        // 10.01 / 1 = 10.01 -> ceil 11 -> 10.99
        BigDecimal sale = calculator.calculate(new BigDecimal("10.01"), template(1, 1, 0, "CHARM_99", 2));
        assertEquals(new BigDecimal("10.99"), sale);
    }

    @Test
    void charm99ClampsAtZeroForZeroCost() {
        // 0 -> ceil 0 -> -0.01 -> clamped to 0.00
        BigDecimal sale = calculator.calculate(BigDecimal.ZERO, template(6.45, 2, 0, "CHARM_99", 2));
        assertEquals(new BigDecimal("0.00"), sale);
    }

    @Test
    void invalidRoundingFallsBackToHalfUp() {
        BigDecimal sale = calculator.calculate(new BigDecimal("14.4"), template(7.2, 2, 1, "BOGUS", 2));
        assertEquals(new BigDecimal("5.00"), sale);
    }
}
