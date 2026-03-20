package com.atguigu.lease.common.jwt;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * JWT 配置（建议通过环境变量/配置中心注入）。
 */
@Data
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /**
     * HMAC secret，建议 >= 32 字符。
     */
    private String secret;

    /**
     * token 过期时间（如 7d / 24h / 3600s）。
     */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration ttl = Duration.ofDays(7);
}
