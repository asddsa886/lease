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

@Service
public class TokenBlacklistService {

    private final StringRedisTemplate stringRedisTemplate;

    public TokenBlacklistService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public boolean isBlacklisted(String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(buildBlacklistKey(token)));
    }

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

    private static String buildBlacklistKey(String token) {
        return RedisConstant.JWT_TOKEN_BLACKLIST_PREFIX + DigestUtils.sha256Hex(token);
    }
}
