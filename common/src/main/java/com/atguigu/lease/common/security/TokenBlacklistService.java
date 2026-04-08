package com.atguigu.lease.common.security;

import com.atguigu.lease.common.constant.RedisConstant.RedisConstant;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.common.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * token 黑名单服务。
 * <p>
 * 作用：
 * 1. 解决 JWT 默认“签发后不可主动失效”的问题；
 * 2. 在退出登录、强制下线等场景下，将 token 拉入 Redis 黑名单；
 * 3. 认证过滤器每次请求时读取黑名单，拒绝已失效 token。
 */
@Service
public class TokenBlacklistService {

    /**
     * Redis 操作模板，用于读写黑名单状态。
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 创建 token 黑名单服务。
     *
     * @param stringRedisTemplate Redis 操作模板，用于存储和查询黑名单 key
     */
    public TokenBlacklistService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 判断某个 token 是否已被拉黑。
     *
     * @param token 待校验的 JWT 字符串
     * @return 若 token 在黑名单中返回 true，否则返回 false；空 token 直接返回 false
     */
    public boolean isBlacklisted(String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(buildBlacklistKey(token)));
    }

    /**
     * 将 token 加入黑名单。
     * <p>
     * 处理逻辑：
     * 1. 先解析 token，拿到其过期时间；
     * 2. 计算 token 距离过期还剩多久；
     * 3. 以“黑名单前缀 + token 哈希”作为 Redis key 写入；
     * 4. TTL 与 token 剩余有效期保持一致，避免黑名单数据无限增长。
     *
     * @param token 需要失效的 JWT 字符串；若为空、已过期或无过期时间，则直接忽略
     */
    public void blacklist(String token) {
        if (!StringUtils.hasText(token)) {
            return;
        }

        Claims claims = JwtUtil.parseToken(token);
        Date expiration = claims.getExpiration();
        if (expiration == null) {
            return;
        }

        long ttlMs = expiration.getTime() - System.currentTimeMillis();
        if (ttlMs <= 0) {
            return;
        }

        stringRedisTemplate.opsForValue().set(buildBlacklistKey(token), ResultCodeEnum.TOKEN_INVALID.name(), ttlMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 生成黑名单 Redis key。
     * <p>
     * 这里使用 token 的 SHA-256 哈希而不是直接存原 token，
     * 是为了避免在 Redis 中直接暴露完整敏感凭证。
     *
     * @param token 原始 JWT 字符串
     * @return 黑名单 Redis key
     */
    private static String buildBlacklistKey(String token) {
        return RedisConstant.JWT_TOKEN_BLACKLIST_PREFIX + DigestUtils.sha256Hex(token);
    }
}
