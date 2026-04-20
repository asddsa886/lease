package com.atguigu.lease.web.app.assistant.service.rag;

import com.atguigu.lease.web.app.assistant.config.AssistantRagProperties;

import java.util.List;

public class DisabledAssistantKnowledgeSearchService implements AssistantKnowledgeSearchService {

    private final AssistantRagProperties properties;

    public DisabledAssistantKnowledgeSearchService(AssistantRagProperties properties) {
        this.properties = properties;
    }

    @Override
    public AssistantKnowledgeSearchResponse search(AssistantKnowledgeSearchRequest request) {
        String message = properties.isEnabled()
                ? "知识库检索服务暂未就绪，请检查 EmbeddingModel 或 Milvus 连接"
                : "知识库检索未启用，请先打开 lease.assistant.rag.enabled";
        return new AssistantKnowledgeSearchResponse(false, message, List.of());
    }
}
