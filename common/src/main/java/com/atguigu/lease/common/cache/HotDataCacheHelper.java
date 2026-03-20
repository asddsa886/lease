package com.atguigu.lease.common.cache;

import com.atguigu.lease.common.constant.RedisConstant.RedisConstant;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
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

    /**
     * Lua 原子释放锁：只有 value 匹配时才删除，避免误删别的线程/进程的锁
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) " +
                    "else return 0 end",
            Long.class
    );

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

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
        String lockKey = "lock:" + key;
        String lockVal = UUID.randomUUID().toString();

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
        boolean locked = tryLock(lockKey, lockVal, Duration.ofSeconds(10));
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
            unlockSafely(lockKey, lockVal);
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

    private boolean tryLock(String lockKey, String lockVal, Duration ttl) {
        try {
            Boolean ok = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, lockVal, ttl);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("HotData tryLock failed, lockKey={}", lockKey, e);
            return false;
        }
    }

    private void unlockSafely(String lockKey, String lockVal) {
        try {
            // 原子校验+删除：防止锁过期后被其他线程抢到又被误删
            stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), lockVal);
        } catch (Exception e) {
            log.warn("HotData unlock failed, lockKey={}", lockKey, e);
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