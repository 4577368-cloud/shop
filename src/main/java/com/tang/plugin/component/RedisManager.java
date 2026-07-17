package com.tang.plugin.component;

import com.tang.common.core.exception.CustomException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Skeleton distributed-lock facade.
 * Replace internals with Redisson when wiring real Redis.
 */
@Slf4j
@Component
public class RedisManager {

    private final ConcurrentHashMap<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();

    @Value("${tang.plugin.lock.enabled:false}")
    private boolean lockEnabled;

    public <T> T lockAround(String lockKey, Supplier<T> supplier) {
        return lockAround(lockKey, 1000, 5000, supplier);
    }

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

    public void lockAround(String lockKey, Runnable runnable) {
        lockAround(lockKey, () -> {
            runnable.run();
            return null;
        });
    }

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
}
