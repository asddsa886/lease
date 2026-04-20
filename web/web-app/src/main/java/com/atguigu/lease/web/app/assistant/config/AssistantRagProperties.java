package com.atguigu.lease.web.app.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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

    private int topK = 3;

    private int maxSnippetLength = 240;

    private String metricType = "L2";

    private int nprobe = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public String getCollectionName() {
        return collectionName;
    }

    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
    }

    public String getIdFieldName() {
        return idFieldName;
    }

    public void setIdFieldName(String idFieldName) {
        this.idFieldName = idFieldName;
    }

    public String getTitleFieldName() {
        return titleFieldName;
    }

    public void setTitleFieldName(String titleFieldName) {
        this.titleFieldName = titleFieldName;
    }

    public String getContentFieldName() {
        return contentFieldName;
    }

    public void setContentFieldName(String contentFieldName) {
        this.contentFieldName = contentFieldName;
    }

    public String getVectorFieldName() {
        return vectorFieldName;
    }

    public void setVectorFieldName(String vectorFieldName) {
        this.vectorFieldName = vectorFieldName;
    }

    public String getMetadataFieldName() {
        return metadataFieldName;
    }

    public void setMetadataFieldName(String metadataFieldName) {
        this.metadataFieldName = metadataFieldName;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getMaxSnippetLength() {
        return maxSnippetLength;
    }

    public void setMaxSnippetLength(int maxSnippetLength) {
        this.maxSnippetLength = maxSnippetLength;
    }

    public String getMetricType() {
        return metricType;
    }

    public void setMetricType(String metricType) {
        this.metricType = metricType;
    }

    public int getNprobe() {
        return nprobe;
    }

    public void setNprobe(int nprobe) {
        this.nprobe = nprobe;
    }
}
