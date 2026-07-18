package com.tang.plugin.service.pricing;

import com.tang.plugin.domain.entity.pricing.PricingTemplate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure unit tests for the deterministic pricing rule. Locks the formula order and, in particular,
 * the CHARM_99 boundary behavior (ceil-to-int then -0.01, clamped at 0.00).
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
        assertNull(calculator.calculate(null, template(0.14, 2, 1, "HALF_UP", 2)));
    }

    @Test
    void nullTemplateYieldsNull() {
        assertNull(calculator.calculate(new BigDecimal("14.4"), null));
    }

    @Test
    void halfUpFollowsFormulaOrder() {
        // 14.4 * 0.14 = 2.016; * 2 = 4.032; + 1 = 5.032; HALF_UP(2) = 5.03
        BigDecimal sale = calculator.calculate(new BigDecimal("14.4"), template(0.14, 2, 1, "HALF_UP", 2));
        assertEquals(new BigDecimal("5.03"), sale);
    }

    @Test
    void halfUpZeroDecimals() {
        // 5.032 -> HALF_UP(0) = 5
        BigDecimal sale = calculator.calculate(new BigDecimal("14.4"), template(0.14, 2, 1, "HALF_UP", 0));
        assertEquals(new BigDecimal("5"), sale);
    }

    @Test
    void ceilRoundsUp() {
        // 5.032 -> CEIL(2) = 5.04
        BigDecimal sale = calculator.calculate(new BigDecimal("14.4"), template(0.14, 2, 1, "CEIL", 2));
        assertEquals(new BigDecimal("5.04"), sale);
    }

    @Test
    void floorRoundsDown() {
        // 5.032 -> FLOOR(2) = 5.03
        BigDecimal sale = calculator.calculate(new BigDecimal("14.4"), template(0.14, 2, 1, "FLOOR", 2));
        assertEquals(new BigDecimal("5.03"), sale);
    }

    @Test
    void charm99FromFraction() {
        // 5.032 -> ceil int 6 -> 6 - 0.01 = 5.99
        BigDecimal sale = calculator.calculate(new BigDecimal("14.4"), template(0.14, 2, 1, "CHARM_99", 2));
        assertEquals(new BigDecimal("5.99"), sale);
    }

    @Test
    void charm99OnExactIntegerStillDrops() {
        // cost 10 * rate 1 * multiplier 1 + 0 = 10.00 -> ceil 10 -> 9.99
        BigDecimal sale = calculator.calculate(new BigDecimal("10"), template(1, 1, 0, "CHARM_99", 2));
        assertEquals(new BigDecimal("9.99"), sale);
    }

    @Test
    void charm99JustAboveIntegerRoundsUpFirst() {
        // 10.01 -> ceil 11 -> 10.99
        BigDecimal sale = calculator.calculate(new BigDecimal("10.01"), template(1, 1, 0, "CHARM_99", 2));
        assertEquals(new BigDecimal("10.99"), sale);
    }

    @Test
    void charm99ClampsAtZeroForZeroCost() {
        // 0 -> ceil 0 -> -0.01 -> clamped to 0.00
        BigDecimal sale = calculator.calculate(BigDecimal.ZERO, template(0.14, 2, 0, "CHARM_99", 2));
        assertEquals(new BigDecimal("0.00"), sale);
    }

    @Test
    void invalidRoundingFallsBackToHalfUp() {
        BigDecimal sale = calculator.calculate(new BigDecimal("14.4"), template(0.14, 2, 1, "BOGUS", 2));
        assertEquals(new BigDecimal("5.03"), sale);
    }
}
