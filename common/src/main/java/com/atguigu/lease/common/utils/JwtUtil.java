package com.atguigu.lease.common.utils;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.jwt.JwtProperties;
import com.atguigu.lease.common.security.TokenClientType;
import com.atguigu.lease.common.result.ResultCodeEnum;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/**
 * JWT 工具：
 * - secret/ttl 可配置（见 JwtProperties）
 * - parseToken 不负责“是否登录”的语义判断（空 token 由各端拦截器处理）
 */
@Slf4j
@Component
public class JwtUtil {

    /**
     * 兜底 secret：避免开发环境未配置导致启动失败；生产请务必配置 JWT_SECRET。
     */
    private static final String DEFAULT_SECRET = "change-me-to-a-long-random-string-at-least-32-chars";

    private static volatile SecretKey secretKey;
    private static volatile Duration ttl = Duration.ofDays(7);

    @Autowired
    public void init(JwtProperties props) {
        String s = props.getSecret();
        if (s == null || s.isBlank()) {
            log.warn("jwt.secret is blank, using default weak secret (dev only)");
            s = DEFAULT_SECRET;
        }

        // secret 长度不足会在 Keys.hmacShaKeyFor 抛异常，这里提前兜底。
        if (s.length() < 32) {
            log.warn("jwt.secret length < 32, padding to avoid runtime error (dev only)");
            s = (s + DEFAULT_SECRET).substring(0, 32);
        }
        secretKey = Keys.hmacShaKeyFor(s.getBytes(StandardCharsets.UTF_8));

        Duration t = props.getTtl();
        if (t != null && !t.isZero() && !t.isNegative()) {
            ttl = t;
        }
    }

    public static String creatToken(Long userId, String username) {
        return creatToken(userId, username, null);
    }

    public static String creatToken(Long userId, String username, TokenClientType clientType) {
        long expMs = System.currentTimeMillis() + ttl.toMillis();
        JwtBuilder builder = Jwts.builder()
                .setExpiration(new Date(expMs))
                .setSubject("LOGIN_USER")
                .claim("userId", userId)
                .claim("username", username);
        if (clientType != null) {
            builder.claim("clientType", clientType.name());
        }
        return builder.signWith(secretKey, SignatureAlgorithm.HS256).compact();
    }

    public static Claims parseToken(String token) {
        // 只做解析；token 是否存在由调用方（拦截器）决定语义与错误码。
        if (token == null || token.isBlank()) {
            throw new LeaseException(ResultCodeEnum.TOKEN_MISSING);
        }

        try {
            Jws<Claims> claimsJws = Jwts.parserBuilder().setSigningKey(secretKey).build().parseClaimsJws(token);
            return claimsJws.getBody();
        } catch (ExpiredJwtException e) {
            throw new LeaseException(ResultCodeEnum.TOKEN_EXPIRED);
        } catch (JwtException e) {
            throw new LeaseException(ResultCodeEnum.TOKEN_INVALID);
        }
    }
}
