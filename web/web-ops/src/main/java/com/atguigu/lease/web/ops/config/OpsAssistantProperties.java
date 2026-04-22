package com.atguigu.lease.web.ops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties("lease.ops.assistant")
public class OpsAssistantProperties {

    private boolean enabled = true;

    private Duration streamTimeout = Duration.ofMinutes(2);

    private Duration sessionTtl = Duration.ofHours(1);
}
