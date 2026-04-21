package com.atguigu.lease.web.admin.assistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "lease.assistant.rag")
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

    private String metricType = "L2";

    private int nprobe = 10;

    private int chunkMaxLength = 600;

    private int chunkOverlap = 80;

    private int titleMaxLength = 255;

    private int contentMaxLength = 4000;

    private int metadataMaxLength = 2000;

    private String indexType = "IVF_FLAT";

    private int nlist = 128;
}
