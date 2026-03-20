package com.atguigu.lease.common.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;

/**
 * 轻量限流组件（Redis + Lua 原子计数，固定窗口）
 * <p>
 * 适用场景：登录、验证码、预约提交等“防刷/防雪崩”的入口保护。
 * <p>
 * 特点：
 * - 单 key 固定窗口计数：windowSec 内最多 limit 次
 * - Lua 脚本保证 INCR + EXPIRE 原子性
 * - 依赖 Redis，适用于多实例/分布式部署
 * <p>
 * 注意：
 * - 这是固定窗口（Fixed Window）实现，简单可靠。极端情况下在窗口边界会有抖动；
 *   若追求更平滑，可升级为滑动窗口（ZSET）或令牌桶。
 */
@Slf4j
@Component
public class RedisRateLimiter {

    /**
     * 返回当前窗口内请求计数（>=1）
     * - 当计数第一次变为 1 时设置过期时间 windowSec
     * - 若超过 limit，仍然返回当前计数（业务侧据此判断是否拦截）
     */
    private static final DefaultRedisScript<Long> INCR_EXPIRE_SCRIPT = new DefaultRedisScript<>(
            // KEYS[1] = key
            // ARGV[1] = windowSec
            "local c = redis.call('incr', KEYS[1]); " +
                    "if c == 1 then redis.call('expire', KEYS[1], tonumber(ARGV[1])); end; " +
                    "return c;",
            Long.class
    );

    /**
     * 滑动窗口（ZSET）限流脚本：
     * - 清理窗口外数据
     * - 统计窗口内数量
     * - 未超限则写入本次请求并设置 key 过期
     * 返回：0=允许；1=限流
     */
    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>(
            // KEYS[1] = key
            // ARGV[1] = nowMs
            // ARGV[2] = windowMs
            // ARGV[3] = limit
            // ARGV[4] = member
            "local key = KEYS[1]; " +
                    "local now = tonumber(ARGV[1]); " +
                    "local window = tonumber(ARGV[2]); " +
                    "local limit = tonumber(ARGV[3]); " +
                    "local member = ARGV[4]; " +
                    "redis.call('ZREMRANGEBYSCORE', key, 0, now - window); " +
                    "local c = redis.call('ZCARD', key); " +
                    "if c >= limit then return 1 end; " +
                    "redis.call('ZADD', key, now, member); " +
                    // key 过期设置为 window 秒（向上取整），避免无限增长
                    "redis.call('PEXPIRE', key, window); " +
                    "return 0;",
            Long.class
    );

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 尝试获取一次额度（固定窗口）。
     *
     * @param key       限流 key（建议包含业务名 + 维度：ip/phone/userId）
     * @param limit     窗口内最大次数
     * @param window    时间窗口
     * @return true=允许；false=被限流
     */
    public boolean tryAcquire(String key, int limit, Duration window) {
        long count = acquireAndGetCount(key, window);
        return count <= limit;
    }

    /**
     * 尝试获取一次额度（滑动窗口）。
     *
     * @return true=允许；false=被限流
     */
    public boolean tryAcquireSlidingWindow(String key, int limit, Duration window) {
        if (limitInvalid(key, window)) {
            return true;
        }
        if (limit <= 0) {
            return true;
        }

        long nowMs = System.currentTimeMillis();
        long windowMs = Math.max(1, window.toMillis());
        String member = nowMs + "-" + Thread.currentThread().getId();

        try {
            Long blocked = stringRedisTemplate.execute(
                    SLIDING_WINDOW_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(nowMs),
                    String.valueOf(windowMs),
                    String.valueOf(limit),
                    member
            );
            return blocked == null || blocked == 0;
        } catch (Exception e) {
            // Redis 异常默认放行，保证可用性；由日志/监控兜底
            log.warn("RateLimit slidingWindow acquire failed, key={}", key, e);
            return true;
        }
    }

    /**
     * 获取当前窗口内计数（会自增）。
     * 当 redis 异常时默认放行（保证可用性），同时记录 warn 便于排查。
     */
    public long acquireAndGetCount(String key, Duration window) {
        if (limitInvalid(key, window)) {
            // 参数不对时不做限流，避免误伤
            return 1L;
        }

        try {
            Long count = stringRedisTemplate.execute(
                    INCR_EXPIRE_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(Math.max(1, window.toSeconds()))
            );
            return count == null ? 1L : count;
        } catch (Exception e) {
            log.warn("RateLimit acquire failed, key={}", key, e);
            return 1L;
        }
    }

    /**
     * 构造限流 key，统一规范，避免散落字符串拼接。
     *
     * @param biz   业务名（如 admin:captcha / app:sms / app:appointment）
     * @param dim   维度名（ip/phone/userId/username 等）
     * @param value 维度值
     */
    public static String key(String biz, String dim, @Nullable Object value) {
        String v = value == null ? "-" : String.valueOf(value);
        return "rl:" + biz + ":" + dim + ":" + v;
    }

    private static boolean limitInvalid(String key, Duration window) {
        if (key == null || key.isBlank()) {
            return true;
        }
        if (window == null || window.isZero() || window.isNegative()) {
            return true;
        }
        return false;
    }
}