package com.atguigu.lease.common.cache;

import com.atguigu.lease.common.constant.RedisConstant.RedisConstant;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Hot data cache helper for read-heavy endpoints.
 *
 * <p>Read order:
 * 1. Caffeine local cache
 * 2. Redis
 * 3. DB loader
 *
 * <p>Features:
 * 1. null-value cache to avoid penetration
 * 2. TTL jitter on Redis to avoid avalanche
 * 3. Redisson lock to reduce cache breakdown
 * 4. short local cache to reduce Redis pressure
 */
@Component
@Slf4j
public class HotDataCacheHelper {

    private static final long DEFAULT_JITTER_SEC = 300;

    @Value("${lease.cache.hot-data-enabled:true}")
    private boolean hotDataCacheEnabled;

    @Value("${lease.cache.local-enabled:true}")
    private boolean localCacheEnabled;

    @Value("${lease.cache.local-max-size:1024}")
    private long localCacheMaxSize;

    @Value("${lease.cache.local-ttl-sec:30}")
    private long localCacheTtlSec;

    @Value("${lease.cache.local-null-ttl-sec:10}")
    private long localNullCacheTtlSec;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedissonClient redissonClient;

    private volatile Cache<String, LocalCacheValue> localCache;

    public <T> T getOrLoad(String key, TypeReference<T> typeReference, Supplier<T> loader) {
        return getOrLoad(key, typeReference, loader,
                RedisConstant.HOT_DATA_CACHE_TTL_SEC,
                RedisConstant.HOT_DATA_NULL_CACHE_TTL_SEC,
                true);
    }

    public <T> T getOrLoad(String key,
                           TypeReference<T> typeReference,
                           Supplier<T> loader,
                           long ttlSec,
                           long nullTtlSec,
                           boolean cacheNull) {
        if (!hotDataCacheEnabled) {
            return loader.get();
        }

        CacheReadResult<T> cached = readCache(key, typeReference, ttlSec, nullTtlSec);
        if (cached.hit()) {
            return cached.value();
        }

        T value = loader.get();
        if (!cacheNull && isNullLike(value)) {
            return value;
        }

        writeCacheSafely(key, value, ttlSec, nullTtlSec);
        return value;
    }

    public <T> T getOrLoadWithLock(String key, TypeReference<T> typeReference, Supplier<T> loader) {
        long ttlSec = RedisConstant.HOT_DATA_CACHE_TTL_SEC;
        long nullTtlSec = RedisConstant.HOT_DATA_NULL_CACHE_TTL_SEC;

        if (!hotDataCacheEnabled) {
            return loader.get();
        }

        CacheReadResult<T> cached = readCache(key, typeReference, ttlSec, nullTtlSec);
        if (cached.hit()) {
            return cached.value();
        }

        RLock lock = redissonClient.getLock("lock:" + key);
        boolean locked = tryLock(lock);
        if (!locked) {
            for (int i = 0; i < 5; i++) {
                sleepSilently(80L + i * 40L);
                CacheReadResult<T> retried = readCache(key, typeReference, ttlSec, nullTtlSec);
                if (retried.hit()) {
                    return retried.value();
                }
            }

            T value = loader.get();
            writeCacheSafely(key, value, ttlSec, nullTtlSec);
            return value;
        }

        try {
            CacheReadResult<T> doubleChecked = readCache(key, typeReference, ttlSec, nullTtlSec);
            if (doubleChecked.hit()) {
                return doubleChecked.value();
            }

            T value = loader.get();
            writeCacheSafely(key, value, ttlSec, nullTtlSec);
            return value;
        } finally {
            unlockSafely(lock);
        }
    }

    public void evict(String key) {
        if (key == null || key.isBlank()) {
            return;
        }

        evictLocal(key);

        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("HotData cache evict failed, key={}", key, e);
        }
    }

    public void evictAll(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }

        List<String> validKeys = new ArrayList<>();
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            validKeys.add(key);
            evictLocal(key);
        }

        if (validKeys.isEmpty()) {
            return;
        }

        try {
            stringRedisTemplate.delete(validKeys);
        } catch (Exception e) {
            log.warn("HotData cache batch evict failed, keys={}", validKeys, e);
        }
    }

    private <T> CacheReadResult<T> readCache(String key,
                                             TypeReference<T> typeReference,
                                             long ttlSec,
                                             long nullTtlSec) {
        CacheReadResult<T> localResult = readLocalCache(key);
        if (localResult.hit()) {
            return localResult;
        }

        String cache = stringRedisTemplate.opsForValue().get(key);
        if (cache == null) {
            return CacheReadResult.miss();
        }

        try {
            T value = objectMapper.readValue(cache, typeReference);
            putLocalCache(key, value, resolveLocalTtlSec(value, ttlSec, nullTtlSec));
            return CacheReadResult.hit(value);
        } catch (Exception e) {
            log.warn("HotData cache deserialize failed, key={}", key, e);
            return CacheReadResult.miss();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> CacheReadResult<T> readLocalCache(String key) {
        Cache<String, LocalCacheValue> currentLocalCache = getLocalCache();
        if (currentLocalCache == null) {
            return CacheReadResult.miss();
        }

        LocalCacheValue cached = currentLocalCache.getIfPresent(key);
        if (cached == null) {
            return CacheReadResult.miss();
        }

        return CacheReadResult.hit((T) cached.value());
    }

    private <T> void writeCacheSafely(String key, T value, long ttlSec, long nullTtlSec) {
        putLocalCache(key, value, resolveLocalTtlSec(value, ttlSec, nullTtlSec));

        try {
            String json = objectMapper.writeValueAsString(value);
            long redisTtlSec = resolveRedisTtlSec(value, ttlSec, nullTtlSec);
            stringRedisTemplate.opsForValue().set(key, json, redisTtlSec, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("HotData cache write failed, key={}", key, e);
        }
    }

    private void putLocalCache(String key, Object value, long ttlSec) {
        Cache<String, LocalCacheValue> currentLocalCache = getLocalCache();
        if (currentLocalCache == null) {
            return;
        }

        try {
            currentLocalCache.put(key, new LocalCacheValue(value, TimeUnit.SECONDS.toNanos(Math.max(1, ttlSec))));
        } catch (Exception e) {
            log.warn("HotData local cache write failed, key={}", key, e);
        }
    }

    private void evictLocal(String key) {
        Cache<String, LocalCacheValue> currentLocalCache = localCache;
        if (currentLocalCache != null) {
            currentLocalCache.invalidate(key);
        }
    }

    private Cache<String, LocalCacheValue> getLocalCache() {
        if (!localCacheEnabled || localCacheMaxSize <= 0) {
            return null;
        }

        Cache<String, LocalCacheValue> currentLocalCache = localCache;
        if (currentLocalCache != null) {
            return currentLocalCache;
        }

        synchronized (this) {
            currentLocalCache = localCache;
            if (currentLocalCache == null) {
                currentLocalCache = Caffeine.newBuilder()
                        .maximumSize(Math.max(1, localCacheMaxSize))
                        .expireAfter(new LocalCacheExpiry())
                        .build();
                localCache = currentLocalCache;
            }
        }
        return currentLocalCache;
    }

    private long resolveRedisTtlSec(Object value, long ttlSec, long nullTtlSec) {
        if (isNullLike(value)) {
            return Math.max(1, nullTtlSec);
        }

        long jitter = ThreadLocalRandom.current().nextLong(0, DEFAULT_JITTER_SEC);
        return Math.max(1, ttlSec + jitter);
    }

    private long resolveLocalTtlSec(Object value, long ttlSec, long nullTtlSec) {
        if (isNullLike(value)) {
            return Math.max(1, Math.min(Math.max(1, nullTtlSec), Math.max(1, localNullCacheTtlSec)));
        }

        return Math.max(1, Math.min(Math.max(1, ttlSec), Math.max(1, localCacheTtlSec)));
    }

    private static boolean isNullLike(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Collection<?>) {
            return ((Collection<?>) value).isEmpty();
        }
        return false;
    }

    private boolean tryLock(RLock lock) {
        try {
            return lock.tryLock();
        } catch (Exception e) {
            log.warn("HotData tryLock failed, lockName={}", lock.getName(), e);
            return false;
        }
    }

    private void unlockSafely(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("HotData unlock failed, lockName={}", lock.getName(), e);
        }
    }

    private static void sleepSilently(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private record LocalCacheValue(Object value, long ttlNanos) {
    }

    private static final class LocalCacheExpiry implements Expiry<String, LocalCacheValue> {

        @Override
        public long expireAfterCreate(String key, LocalCacheValue value, long currentTime) {
            return value.ttlNanos();
        }

        @Override
        public long expireAfterUpdate(String key, LocalCacheValue value, long currentTime, long currentDuration) {
            return value.ttlNanos();
        }

        @Override
        public long expireAfterRead(String key, LocalCacheValue value, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }

    private record CacheReadResult<T>(boolean hit, T value) {

        private static <T> CacheReadResult<T> hit(T value) {
            return new CacheReadResult<>(true, value);
        }

        private static <T> CacheReadResult<T> miss() {
            return new CacheReadResult<>(false, null);
        }
    }
}
