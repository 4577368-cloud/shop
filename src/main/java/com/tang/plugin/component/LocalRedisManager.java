package com.tang.plugin.component;

import com.tang.common.core.exception.CustomException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * In-process lock / map fallback when Redisson is not configured (local H2 profile).
 */
@Slf4j
@Component
@ConditionalOnMissingBean(name = "redissonRedisManager")
public class LocalRedisManager implements RedisManager {

    private final ConcurrentHashMap<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> strings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> objects = new ConcurrentHashMap<>();

    @Value("${tang.plugin.lock.enabled:false}")
    private boolean lockEnabled;

    @Override
    public <T> T lockAround(String lockKey, Supplier<T> supplier) {
        return lockAround(lockKey, 1000, 5000, supplier);
    }

    @Override
    public <T> T lockAround(String lockKey, long waitMs, long leaseMs, Supplier<T> supplier) {
        if (!lockEnabled) {
            return supplier.get();
        }
        ReentrantLock lock = localLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        boolean locked;
        try {
            locked = lock.tryLock(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomException("Acquire lock interrupted: " + lockKey, e);
        }
        if (!locked) {
            throw new CustomException("Acquire lock failed: " + lockKey);
        }
        try {
            return supplier.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void lockAround(String lockKey, Runnable runnable) {
        lockAround(lockKey, () -> {
            runnable.run();
            return null;
        });
    }

    @Override
    public void lockAround(String lockKey, long waitMs, long leaseMs, Runnable runnable) {
        lockAround(lockKey, waitMs, leaseMs, () -> {
            runnable.run();
            return null;
        });
    }

    @Override
    public <T> T lockAroundCallable(String lockKey, Callable<T> callable) {
        return lockAround(lockKey, () -> {
            try {
                return callable.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new CustomException("Locked callable failed: " + lockKey, e);
            }
        });
    }

    @Override
    public void setString(String key, String value) {
        strings.put(key, value);
    }

    @Override
    public void setString(String key, String value, long ttlSeconds) {
        strings.put(key, value);
    }

    @Override
    public String getString(String key) {
        return strings.get(key);
    }

    @Override
    public void delString(String key) {
        strings.remove(key);
    }

    @Override
    public <T> void setObject(String key, T obj) {
        objects.put(key, obj);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getObject(String key) {
        return (T) objects.get(key);
    }

    @Override
    public Map<String, String> getMap(String key) {
        return Map.of();
    }

    @Override
    public Set<String> getSet(String key) {
        return Set.of();
    }
}
