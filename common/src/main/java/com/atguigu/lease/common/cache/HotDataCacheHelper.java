package com.atguigu.lease.common.cache;

import com.atguigu.lease.common.constant.RedisConstant.RedisConstant;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 热数据缓存通用封装（读多写少场景）
 * <p>
 * 目标：
 * 1) 统一 Key/TTL/空值缓存/随机抖动 策略
 * 2) 反序列化失败自动降级（不影响可用性）
 * 3) 可选互斥锁，降低缓存击穿导致的 DB 峰值
 */
@Component
@Slf4j
public class HotDataCacheHelper {

    private static final long DEFAULT_JITTER_SEC = 300;

    @Value("${lease.cache.hot-data-enabled:true}")
    private boolean hotDataCacheEnabled;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedissonClient redissonClient;

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

        String cache = stringRedisTemplate.opsForValue().get(key);
        if (cache != null) {
            try {
                return objectMapper.readValue(cache, typeReference);
            } catch (Exception e) {
                log.warn("HotData cache deserialize failed, key={}", key, e);
            }
        }

        T value = loader.get();

        if (!cacheNull && isNullLike(value)) {
            return value;
        }

        writeCacheSafely(key, value, ttlSec, nullTtlSec);
        return value;
    }

    /**
     * 带互斥锁的 getOrLoad：用于热点 key 的首次加载/失效瞬间，避免高并发打穿 DB。
     * <p>
     * 说明：锁只用于保护“回源加载”阶段；拿不到锁会短暂等待并重试读取缓存。
     */
    public <T> T getOrLoadWithLock(String key, TypeReference<T> typeReference, Supplier<T> loader) {
        if (!hotDataCacheEnabled) {
            return loader.get();
        }

        String lockKey = "lock:" + key;

        // 1) 先尝试读缓存（快路径）
        String cache = stringRedisTemplate.opsForValue().get(key);
        if (cache != null) {
            try {
                return objectMapper.readValue(cache, typeReference);
            } catch (Exception e) {
                log.warn("HotData cache deserialize failed, key={}", key, e);
            }
        }

        // 2) 尝试抢锁
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = tryLock(lock);
        if (!locked) {
            // 3) 没抢到锁：短暂等待，期间不断尝试读缓存（期望别的线程回源后写入）
            for (int i = 0; i < 5; i++) {
                sleepSilently(80L + i * 40L);
                String again = stringRedisTemplate.opsForValue().get(key);
                if (again != null) {
                    try {
                        return objectMapper.readValue(again, typeReference);
                    } catch (Exception e) {
                        log.warn("HotData cache deserialize failed after wait, key={}", key, e);
                        break;
                    }
                }
            }
            // 兜底：直接回源（避免无限等待）
            T value = loader.get();
            writeCacheSafely(key, value, RedisConstant.HOT_DATA_CACHE_TTL_SEC, RedisConstant.HOT_DATA_NULL_CACHE_TTL_SEC);
            return value;
        }

        try {
            // 双检：防止在抢锁期间已被写入
            String doubleCheck = stringRedisTemplate.opsForValue().get(key);
            if (doubleCheck != null) {
                try {
                    return objectMapper.readValue(doubleCheck, typeReference);
                } catch (Exception e) {
                    log.warn("HotData cache deserialize failed after lock, key={}", key, e);
                }
            }

            T value = loader.get();
            writeCacheSafely(key, value, RedisConstant.HOT_DATA_CACHE_TTL_SEC, RedisConstant.HOT_DATA_NULL_CACHE_TTL_SEC);
            return value;
        } finally {
            unlockSafely(lock);
        }
    }

    private <T> void writeCacheSafely(String key, T value, long ttlSec, long nullTtlSec) {
        try {
            String json = objectMapper.writeValueAsString(value);

            long finalTtlSec;
            if (isNullLike(value)) {
                finalTtlSec = Math.max(1, nullTtlSec);
            } else {
                long jitter = ThreadLocalRandom.current().nextLong(0, DEFAULT_JITTER_SEC);
                finalTtlSec = Math.max(1, ttlSec + jitter);
            }

            stringRedisTemplate.opsForValue().set(key, json, finalTtlSec, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("HotData cache write failed, key={}", key, e);
        }
    }

    private static boolean isNullLike(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Collection) {
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
}
