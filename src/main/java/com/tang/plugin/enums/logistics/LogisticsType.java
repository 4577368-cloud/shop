package com.tang.plugin.enums.logistics;

/**
 * Product logistics attribute class used for lane matching (Phase 2). Phase 1 only classifies and
 * persists; labels are for operator-facing UI.
 */
public enum LogisticsType {
    GENERAL("普货"),
    APPAREL("服装"),
    FOOD("食品"),
    BATTERY_MAGNETIC("带电 / 带磁"),
    BLADE("刀具"),
    OTHER("其他特殊品类");

    private final String label;

    LogisticsType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean isHighRisk() {
        return this == BATTERY_MAGNETIC || this == FOOD || this == BLADE;
    }
}
