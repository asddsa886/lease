package com.atguigu.lease.web.app.chat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "app.ai.assistant")
public class AssistantProperties {

    private boolean enabled = false;

    private String provider = "openai-compatible";

    private String baseUrl;

    private String modelName;

    private String apiKey;

    private Double temperature = 0.2D;

    private Duration timeout = Duration.ofSeconds(60);

    private Integer maxSearchResults = 5;
}
