package com.tang.plugin.service.pricing;

import com.tang.plugin.domain.entity.pricing.PricingTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure, side-effect-free sale-price calculation. Kept separate from persistence/orchestration so
 * the rule is unit-testable in isolation. All money math is BigDecimal; template doubles are read
 * via {@link BigDecimal#valueOf(double)} to avoid binary float artifacts.
 *
 * <p>Pipeline (fixed order): converted = cost / exchangeRate (CNY→USD style, e.g. 10/6.45);
 * marked = converted * multiplier + addend; sale = applyRounding(marked, strategy, decimals).
 *
 * <p>{@code exchangeRate} means "how many source-currency units equal 1 target-currency unit"
 * (e.g. 6.45 CNY = 1 USD), so conversion is division — never multiply.
 */
@Component
public class PriceCalculator {

    private static final BigDecimal CHARM_DELTA = new BigDecimal("0.01");
    private static final int FX_SCALE = 8;

    /**
     * @return sale price in target currency, or {@code null} when {@code cost} is null (unknown
     * procurement price — caller keeps the item but leaves estimatedSalePrice empty).
     */
    public BigDecimal calculate(BigDecimal cost, PricingTemplate template) {
        if (cost == null || template == null) {
            return null;
        }
        BigDecimal rate = BigDecimal.valueOf(nvl(template.getExchangeRate(), 0d));
        if (rate.signum() <= 0) {
            return null;
        }
        BigDecimal multiplier = BigDecimal.valueOf(nvl(template.getMultiplier(), 1d));
        BigDecimal addend = BigDecimal.valueOf(nvl(template.getAddend(), 0d));
        int decimals = template.getDecimals() == null ? 2 : template.getDecimals();

        BigDecimal converted = cost.divide(rate, FX_SCALE, RoundingMode.HALF_UP);
        BigDecimal marked = converted.multiply(multiplier).add(addend);
        return applyRounding(marked, RoundingStrategy.fromOrDefault(template.getRoundingStrategy()), decimals);
    }

    private static BigDecimal applyRounding(BigDecimal marked, RoundingStrategy strategy, int decimals) {
        return switch (strategy) {
            case HALF_UP -> marked.setScale(decimals, RoundingMode.HALF_UP);
            case CEIL -> marked.setScale(decimals, RoundingMode.CEILING);
            case FLOOR -> marked.setScale(decimals, RoundingMode.FLOOR);
            case CHARM_99 -> charm99(marked);
        };
    }

    /** Ceil to integer, then N - 0.01 (2 decimals). Clamped so the result is never negative. */
    private static BigDecimal charm99(BigDecimal marked) {
        BigDecimal ceilInt = marked.setScale(0, RoundingMode.CEILING);
        BigDecimal result = ceilInt.subtract(CHARM_DELTA);
        if (result.signum() < 0) {
            result = BigDecimal.ZERO;
        }
        return result.setScale(2, RoundingMode.HALF_UP);
    }

    private static double nvl(Double value, double fallback) {
        return value == null ? fallback : value;
    }
}
