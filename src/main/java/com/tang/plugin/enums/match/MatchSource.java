package com.tang.plugin.enums.match;

/**
 * Source of a product match candidate. P1 only implements MANUAL;
 * RULE / IMAGE / AI are reserved for later phases (SPI extension points).
 */
public enum MatchSource {
    MANUAL,
    RULE,
    IMAGE,
    AI,
    /** 1:1 link established when publishing a Tangbuy-catalog product (route B) — no matching needed. */
    CATALOG
}
