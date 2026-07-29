package com.tang.plugin.component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Distributed cache / lock facade.
 * LocalRedisManager (default) or RedissonRedisManager (when Redis is configured).
 */
public interface RedisManager {

    <T> T lockAround(String lockKey, Supplier<T> supplier);

    <T> T lockAround(String lockKey, long waitMs, long leaseMs, Supplier<T> supplier);

    void lockAround(String lockKey, Runnable runnable);

    void lockAround(String lockKey, long waitMs, long leaseMs, Runnable runnable);

    <T> T lockAroundCallable(String lockKey, Callable<T> callable);

    default void setString(String key, String value) {
    }

    default void setString(String key, String value, long ttlSeconds) {
    }

    default String getString(String key) {
        return null;
    }

    default void delString(String key) {
    }

    default <T> void setObject(String key, T obj) {
    }

    default <T> void setObject(String key, T obj, long ttlSeconds) {
    }

    default <T> T getObject(String key) {
        return null;
    }

    default void setMap(String key, Map<String, String> value) {
    }

    default Map<String, String> getMap(String key) {
        return Map.of();
    }

    default Set<String> getSet(String key) {
        return Set.of();
    }
}
