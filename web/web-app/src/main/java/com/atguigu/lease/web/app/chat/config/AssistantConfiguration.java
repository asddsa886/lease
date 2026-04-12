package com.atguigu.lease.web.app.chat.config;

import com.atguigu.lease.web.app.chat.memory.AssistantMongoChatMemoryStore;
import com.atguigu.lease.web.app.chat.rag.LocalKnowledgeContentRetriever;
import com.atguigu.lease.web.app.chat.service.AppointmentActionAnalyzer;
import com.atguigu.lease.web.app.chat.service.BusinessIntentAnalyzer;
import com.atguigu.lease.web.app.chat.service.RentalAssistant;
import com.atguigu.lease.web.app.chat.service.StreamingToolFirstRentalAssistant;
import com.atguigu.lease.web.app.chat.service.StreamingRentalAssistant;
import com.atguigu.lease.web.app.chat.service.ToolFirstRentalAssistant;
import com.atguigu.lease.web.app.chat.tool.RentalAssistantTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Configuration
@EnableConfigurationProperties(AssistantProperties.class)
public class AssistantConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.ai.assistant", name = "enabled", havingValue = "true")
    public ChatModel assistantChatModel(AssistantProperties assistantProperties) {
        validateOpenAiCompatibleConfig(assistantProperties);
        return OpenAiChatModel.builder()
                .baseUrl(assistantProperties.getBaseUrl())
                .apiKey(assistantProperties.getApiKey())
                .modelName(assistantProperties.getModelName())
                .temperature(assistantProperties.getTemperature())
                .timeout(assistantProperties.getTimeout())
                .maxRetries(resolveMaxRetries(assistantProperties))
                .logRequests(assistantProperties.isLogRequests())
                .logResponses(assistantProperties.isLogResponses())
                .logger(LoggerFactory.getLogger("assistant.openai.chat"))
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai.assistant", name = "enabled", havingValue = "true")
    public StreamingChatModel assistantStreamingChatModel(AssistantProperties assistantProperties) {
        validateOpenAiCompatibleConfig(assistantProperties);
        return OpenAiStreamingChatModel.builder()
                .baseUrl(assistantProperties.getBaseUrl())
                .apiKey(assistantProperties.getApiKey())
                .modelName(assistantProperties.getModelName())
                .temperature(assistantProperties.getTemperature())
                .timeout(assistantProperties.getTimeout())
                .logRequests(assistantProperties.isLogRequests())
                .logResponses(assistantProperties.isLogResponses())
                .logger(LoggerFactory.getLogger("assistant.openai.streaming"))
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai.assistant", name = "enabled", havingValue = "true")
    public AssistantMongoChatMemoryStore assistantChatMemoryStore(AssistantProperties assistantProperties,
                                                                  ObjectProvider<MongoTemplate> mongoTemplateProvider,
                                                                  ObjectMapper objectMapper) {
        return new AssistantMongoChatMemoryStore(assistantProperties, mongoTemplateProvider, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai.assistant", name = "enabled", havingValue = "true")
    public ChatMemoryProvider assistantChatMemoryProvider(AssistantProperties assistantProperties,
                                                          AssistantMongoChatMemoryStore assistantChatMemoryStore) {
        int maxMessages = assistantProperties.getMaxMemoryMessages() == null
                ? 20
                : assistantProperties.getMaxMemoryMessages();
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(Math.max(maxMessages, 4))
                .chatMemoryStore(assistantChatMemoryStore)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai.assistant", name = "enabled", havingValue = "true")
    public ContentRetriever assistantContentRetriever(AssistantProperties assistantProperties,
                                                      ResourcePatternResolver resourcePatternResolver) {
        return new LocalKnowledgeContentRetriever(assistantProperties, resourcePatternResolver);
    }

    @Bean
    @ConditionalOnBean(ChatModel.class)
    public RentalAssistant rentalAssistant(ChatModel assistantChatModel,
                                           RentalAssistantTools rentalAssistantTools,
                                           ChatMemoryProvider assistantChatMemoryProvider,
                                           ContentRetriever assistantContentRetriever,
                                           AssistantProperties assistantProperties) {
        AiServices<RentalAssistant> builder = AiServices.builder(RentalAssistant.class)
                .chatModel(assistantChatModel)
                .tools(rentalAssistantTools);

        if (assistantProperties.isMemoryEnabled()) {
            builder = builder.chatMemoryProvider(assistantChatMemoryProvider);
        }
        if (assistantProperties.isRagEnabled()) {
            builder = builder.contentRetriever(assistantContentRetriever);
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnBean(StreamingChatModel.class)
    public StreamingRentalAssistant streamingRentalAssistant(StreamingChatModel assistantStreamingChatModel,
                                                             RentalAssistantTools rentalAssistantTools,
                                                             ChatMemoryProvider assistantChatMemoryProvider,
                                                             ContentRetriever assistantContentRetriever,
                                                             AssistantProperties assistantProperties) {
        AiServices<StreamingRentalAssistant> builder = AiServices.builder(StreamingRentalAssistant.class)
                .streamingChatModel(assistantStreamingChatModel)
                .tools(rentalAssistantTools);

        if (assistantProperties.isMemoryEnabled()) {
            builder = builder.chatMemoryProvider(assistantChatMemoryProvider);
        }
        if (assistantProperties.isRagEnabled()) {
            builder = builder.contentRetriever(assistantContentRetriever);
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnBean(ChatModel.class)
    public ToolFirstRentalAssistant toolFirstRentalAssistant(ChatModel assistantChatModel,
                                                             RentalAssistantTools rentalAssistantTools,
                                                             ChatMemoryProvider assistantChatMemoryProvider,
                                                             AssistantProperties assistantProperties) {
        AiServices<ToolFirstRentalAssistant> builder = AiServices.builder(ToolFirstRentalAssistant.class)
                .chatModel(assistantChatModel)
                .tools(rentalAssistantTools);

        if (assistantProperties.isMemoryEnabled()) {
            builder = builder.chatMemoryProvider(assistantChatMemoryProvider);
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnBean(StreamingChatModel.class)
    public StreamingToolFirstRentalAssistant streamingToolFirstRentalAssistant(StreamingChatModel assistantStreamingChatModel,
                                                                               RentalAssistantTools rentalAssistantTools,
                                                                               ChatMemoryProvider assistantChatMemoryProvider,
                                                                               AssistantProperties assistantProperties) {
        AiServices<StreamingToolFirstRentalAssistant> builder = AiServices.builder(StreamingToolFirstRentalAssistant.class)
                .streamingChatModel(assistantStreamingChatModel)
                .tools(rentalAssistantTools);

        if (assistantProperties.isMemoryEnabled()) {
            builder = builder.chatMemoryProvider(assistantChatMemoryProvider);
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnBean(ChatModel.class)
    public BusinessIntentAnalyzer businessIntentAnalyzer(ChatModel assistantChatModel) {
        return AiServices.builder(BusinessIntentAnalyzer.class)
                .chatModel(assistantChatModel)
                .build();
    }

    @Bean
    @ConditionalOnBean(ChatModel.class)
    public AppointmentActionAnalyzer appointmentActionAnalyzer(ChatModel assistantChatModel) {
        return AiServices.builder(AppointmentActionAnalyzer.class)
                .chatModel(assistantChatModel)
                .build();
    }

    private void validateOpenAiCompatibleConfig(AssistantProperties assistantProperties) {
        String provider = normalizeProvider(assistantProperties.getProvider());
        if (!"openai-compatible".equals(provider) && !"openai".equals(provider)) {
            throw new IllegalStateException("当前仅支持 openai-compatible 或 openai provider");
        }
        if (!StringUtils.hasText(assistantProperties.getBaseUrl())
                || !StringUtils.hasText(assistantProperties.getModelName())
                || !StringUtils.hasText(assistantProperties.getApiKey())) {
            throw new IllegalStateException("启用智能助手时，必须配置 base-url、model-name 和 api-key");
        }
    }

    private String normalizeProvider(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }

    private int resolveMaxRetries(AssistantProperties assistantProperties) {
        Integer maxRetries = assistantProperties.getMaxRetries();
        return maxRetries == null ? 2 : Math.max(maxRetries, 0);
    }
}
