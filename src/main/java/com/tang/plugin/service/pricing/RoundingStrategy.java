package com.tang.plugin.service.pricing;

import java.util.Arrays;

/**
 * Supported rounding strategies for the pricing template. Deliberately a small, closed set.
 * <ul>
 *   <li>{@code HALF_UP} — round to {@code decimals} using HALF_UP.</li>
 *   <li>{@code CEIL}    — round up to {@code decimals}.</li>
 *   <li>{@code FLOOR}   — round down to {@code decimals}.</li>
 *   <li>{@code CHARM_99}— ceil to integer N, result = N - 0.01 (charm x.99), always 2 decimals,
 *       clamped to 0.00 so it never goes negative.</li>
 * </ul>
 */
public enum RoundingStrategy {
    HALF_UP,
    CEIL,
    FLOOR,
    CHARM_99;

    public static boolean isValid(String value) {
        return value != null && Arrays.stream(values()).anyMatch(s -> s.name().equals(value));
    }

    /** Parse a stored/request value, falling back to HALF_UP for unknown/blank input. */
    public static RoundingStrategy fromOrDefault(String value) {
        return isValid(value) ? valueOf(value) : HALF_UP;
    }
}
