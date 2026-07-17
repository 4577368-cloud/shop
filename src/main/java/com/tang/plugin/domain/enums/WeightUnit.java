package com.tang.plugin.domain.enums;

/**
 * Weight units for product conversion. Final storage unit is GRAMS.
 */
public enum WeightUnit {
    GRAMS,
    KILOGRAMS,
    OUNCES,
    POUNDS;

    /**
     * Convert weight to target unit.
     */
    public static double convertTo(double value, WeightUnit from, WeightUnit to) {
        if (from == to) {
            return value;
        }
        double grams = toGrams(value, from);
        return fromGrams(grams, to);
    }

    public static double convertTo(double value, WeightUnit from) {
        return convertTo(value, from, GRAMS);
    }

    private static double toGrams(double value, WeightUnit from) {
        return switch (from) {
            case GRAMS -> value;
            case KILOGRAMS -> value * 1000D;
            case OUNCES -> value * 28.349523125D;
            case POUNDS -> value * 453.59237D;
        };
    }

    private static double fromGrams(double grams, WeightUnit to) {
        return switch (to) {
            case GRAMS -> grams;
            case KILOGRAMS -> grams / 1000D;
            case OUNCES -> grams / 28.349523125D;
            case POUNDS -> grams / 453.59237D;
        };
    }
}
