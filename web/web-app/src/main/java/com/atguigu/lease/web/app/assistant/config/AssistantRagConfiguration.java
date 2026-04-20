package com.atguigu.lease.web.app.assistant.config;

import com.atguigu.lease.web.app.assistant.service.rag.AssistantKnowledgeSearchService;
import com.atguigu.lease.web.app.assistant.service.rag.DisabledAssistantKnowledgeSearchService;
import com.atguigu.lease.web.app.assistant.service.rag.MilvusAssistantKnowledgeSearchService;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AssistantRagProperties.class)
public class AssistantRagConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "lease.assistant.rag", name = "enabled", havingValue = "true")
    public MilvusServiceClient assistantMilvusServiceClient(AssistantRagProperties properties) {
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(properties.getHost())
                .withPort(properties.getPort())
                .withConnectTimeout(properties.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
        return new MilvusServiceClient(connectParam);
    }

    @Bean
    @ConditionalOnBean({MilvusServiceClient.class, EmbeddingModel.class})
    @ConditionalOnProperty(prefix = "lease.assistant.rag", name = "enabled", havingValue = "true")
    public AssistantKnowledgeSearchService assistantKnowledgeSearchService(MilvusServiceClient milvusServiceClient,
                                                                           EmbeddingModel embeddingModel,
                                                                           AssistantRagProperties properties) {
        return new MilvusAssistantKnowledgeSearchService(milvusServiceClient, embeddingModel, properties);
    }

    @Bean
    @ConditionalOnMissingBean(AssistantKnowledgeSearchService.class)
    public AssistantKnowledgeSearchService disabledAssistantKnowledgeSearchService(AssistantRagProperties properties) {
        return new DisabledAssistantKnowledgeSearchService(properties);
    }
}
