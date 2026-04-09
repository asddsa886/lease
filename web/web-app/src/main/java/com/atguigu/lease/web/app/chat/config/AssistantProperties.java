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

    private Integer maxRetries = 2;

    private boolean logRequests = false;

    private boolean logResponses = false;

    private Integer maxSearchResults = 5;

    private boolean memoryEnabled = true;

    private boolean mongoMemoryEnabled = true;

    private Integer maxMemoryMessages = 20;

    private boolean ragEnabled = true;

    private String knowledgeLocation = "classpath*:assistant-knowledge/*.md";

    private Integer maxKnowledgeMatches = 2;

    private Integer maxKnowledgeChars = 1200;
}
