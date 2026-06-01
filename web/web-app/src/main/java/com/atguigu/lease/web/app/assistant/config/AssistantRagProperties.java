package com.atguigu.lease.web.app.assistant.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lease.assistant.rag")
@Getter
@Setter
public class AssistantRagProperties {

    private boolean enabled;

    private String host = "localhost";

    private int port = 19530;

    private long connectTimeoutMs = 5000L;

    private String collectionName = "lease_assistant_knowledge";

    private String idFieldName = "id";

    private String titleFieldName = "title";

    private String contentFieldName = "content";

    private String vectorFieldName = "vector";

    private String metadataFieldName = "metadata";

    private int topK = 3;

    private int maxSnippetLength = 240;

    private String metricType = "L2";

    private int nprobe = 10;
}
