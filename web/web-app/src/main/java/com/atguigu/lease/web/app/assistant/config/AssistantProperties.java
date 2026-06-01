package com.atguigu.lease.web.app.assistant.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "lease.assistant")
@Getter
@Setter
public class AssistantProperties {

    private boolean enabled;

    private Duration conversationTtl = Duration.ofDays(7);

    private int maxHistoryMessages = 12;

    private Duration streamTimeout = Duration.ofMinutes(2);
}
