package com.tang.plugin.component;

import com.tang.common.core.exception.CustomException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RSet;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redisson-backed RedisManager. Active when tang.plugin.redis.enabled=true.
 * k8s-only: Render fat jar excludes Redisson (see Maven profile {@code render}).
 */
@Slf4j
@Service("redissonRedisManager")
@Profile("k8s")
@ConditionalOnClass(RedissonClient.class)
@ConditionalOnProperty(name = "tang.plugin.redis.enabled", havingValue = "true")
@ConditionalOnBean(RedissonClient.class)
public class RedissonRedisManager implements RedisManager {

    public static final String REDIS_APP_PREFIX = "tang-source-plugin";

    private final RedissonClient redissonClient;

    public RedissonRedisManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    private String wrapPrefix(String key) {
        return REDIS_APP_PREFIX + ":" + key;
    }

    @Override
    public <T> T lockAround(String lockKey, Supplier<T> supplier) {
        return lockAround(lockKey, 3000, 10000, supplier);
    }

    @Override
    public <T> T lockAround(String lockKey, long waitMs, long leaseMs, Supplier<T> supplier) {
        String redisKey = wrapPrefix(lockKey);
        RLock lock = redissonClient.getLock(redisKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(waitMs, leaseMs, TimeUnit.MILLISECONDS);
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
                try {
                    lock.unlock();
                } catch (Exception ignored) {
                    // ignore
                }
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
        redissonClient.getBucket(wrapPrefix(key)).set(value);
    }

    @Override
    public void setString(String key, String value, long ttlSeconds) {
        redissonClient.<String>getBucket(wrapPrefix(key)).set(value, ttlSeconds, TimeUnit.SECONDS);
    }

    @Override
    public String getString(String key) {
        RBucket<String> bucket = redissonClient.getBucket(wrapPrefix(key));
        return bucket.get();
    }

    @Override
    public void delString(String key) {
        redissonClient.getBucket(wrapPrefix(key)).delete();
    }

    @Override
    public <T> void setObject(String key, T obj) {
        redissonClient.getBucket(wrapPrefix(key)).set(obj);
    }

    @Override
    public <T> void setObject(String key, T obj, long ttlSeconds) {
        redissonClient.<T>getBucket(wrapPrefix(key)).set(obj, ttlSeconds, TimeUnit.SECONDS);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getObject(String key) {
        return (T) redissonClient.getBucket(wrapPrefix(key)).get();
    }

    @Override
    public void setMap(String key, Map<String, String> value) {
        redissonClient.getMap(wrapPrefix(key)).putAll(value);
    }

    @Override
    public Map<String, String> getMap(String key) {
        Map<String, String> result = new HashMap<>();
        RMap<Object, Object> map = redissonClient.getMap(wrapPrefix(key));
        for (Map.Entry<Object, Object> e : map.readAllMap().entrySet()) {
            result.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        return result;
    }

    @Override
    public Set<String> getSet(String key) {
        try {
            RSet<String> set = redissonClient.getSet(wrapPrefix(key));
            return set.readAll();
        } catch (Exception e) {
            log.error("REDIS_ERROR_GET_SET key={}", key, e);
            return new HashSet<>(0);
        }
    }

    /** Shopify Admin GraphQL rate limit helper (default 8 req/sec per shop). */
    public boolean tryAcquireShopifyRate(String shopName, long rate, long rateIntervalSeconds) {
        String key = wrapPrefix("shopify:rate:" + shopName);
        RRateLimiter limiter = redissonClient.getRateLimiter(key);
        limiter.trySetRate(RateType.OVERALL, rate, rateIntervalSeconds, RateIntervalUnit.SECONDS);
        return limiter.tryAcquire(1);
    }
}
