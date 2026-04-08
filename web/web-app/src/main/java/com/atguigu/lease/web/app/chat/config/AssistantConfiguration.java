package com.atguigu.lease.web.app.chat.config;

import com.atguigu.lease.web.app.chat.service.RentalAssistant;
import com.atguigu.lease.web.app.chat.service.StreamingRentalAssistant;
import com.atguigu.lease.web.app.chat.tool.RentalAssistantTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                .build();
    }

    @Bean
    @ConditionalOnBean(ChatModel.class)
    public RentalAssistant rentalAssistant(ChatModel assistantChatModel, RentalAssistantTools rentalAssistantTools) {
        return AiServices.builder(RentalAssistant.class)
                .chatModel(assistantChatModel)
                .tools(rentalAssistantTools)
                .build();
    }

    @Bean
    @ConditionalOnBean(StreamingChatModel.class)
    public StreamingRentalAssistant streamingRentalAssistant(StreamingChatModel assistantStreamingChatModel,
                                                             RentalAssistantTools rentalAssistantTools) {
        return AiServices.builder(StreamingRentalAssistant.class)
                .streamingChatModel(assistantStreamingChatModel)
                .tools(rentalAssistantTools)
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
}
