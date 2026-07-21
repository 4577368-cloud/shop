package com.tang.plugin.utils;

import org.springframework.jdbc.support.KeyHolder;

/**
 * PostgreSQL drivers may return the full inserted row in {@link KeyHolder#getKeys()} when
 * {@code Statement.RETURN_GENERATED_KEYS} is used. Prefer {@code new String[] {"id"}} on
 * {@code prepareStatement} and fall back to {@code getKeys().get("id")}.
 */
public final class GeneratedKeySupport {

    private GeneratedKeySupport() {
    }

    public static Long resolveId(KeyHolder keyHolder) {
        if (keyHolder == null) {
            return null;
        }
        Number key = keyHolder.getKey();
        if (key == null && keyHolder.getKeys() != null && keyHolder.getKeys().containsKey("id")) {
            key = (Number) keyHolder.getKeys().get("id");
        }
        return key != null ? key.longValue() : null;
    }
}
