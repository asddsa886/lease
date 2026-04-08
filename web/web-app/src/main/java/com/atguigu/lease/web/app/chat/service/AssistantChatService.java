package com.atguigu.lease.web.app.chat.service;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.web.app.chat.config.AssistantProperties;
import com.atguigu.lease.web.app.chat.dto.AssistantChatResponseVo;
import com.atguigu.lease.web.app.chat.memory.AssistantMongoChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantChatService {

    private static final String DEFAULT_EMPTY_REPLY = "我当前没有生成有效回复，请换个问法再试。";

    private final AssistantProperties assistantProperties;
    private final ObjectProvider<RentalAssistant> rentalAssistantProvider;
    private final ObjectProvider<StreamingRentalAssistant> streamingRentalAssistantProvider;
    private final ObjectProvider<AssistantMongoChatMemoryStore> assistantChatMemoryStoreProvider;

    public AssistantChatResponseVo chat(String message) {
        return chat(message, null);
    }

    public AssistantChatResponseVo chat(String message, String conversationId) {
        String question = normalizeQuestion(message);
        String resolvedConversationId = resolveConversationId(conversationId);
        if (!assistantProperties.isEnabled()) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "智能助手未启用");
        }

        RentalAssistant rentalAssistant = rentalAssistantProvider.getIfAvailable();
        if (rentalAssistant == null) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "智能助手尚未完成配置");
        }

        try {
            String reply = invokeAssistantWithRecovery(rentalAssistant, resolvedConversationId, question);
            String formattedReply = formatReply(reply);
            return new AssistantChatResponseVo(
                    resolvedConversationId,
                    formattedReply,
                    splitParagraphs(formattedReply)
            );
        } catch (LeaseException e) {
            throw e;
        } catch (Exception e) {
            log.error("Assistant chat failed", e);
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "智能助手调用失败，请稍后重试");
        }
    }

    public SseEmitter stream(String message) {
        return stream(message, null);
    }

    public SseEmitter stream(String message, String conversationId) {
        SseEmitter emitter = new SseEmitter(resolveStreamTimeoutMillis());
        String question;
        String resolvedConversationId;
        try {
            question = normalizeQuestion(message);
            resolvedConversationId = resolveConversationId(conversationId);
            validateStreamReady();
        } catch (Exception e) {
            emitErrorAndComplete(emitter, e, conversationId);
            return emitter;
        }

        RentalAssistant rentalAssistant = rentalAssistantProvider.getIfAvailable();
        if (rentalAssistant == null) {
            emitErrorAndComplete(
                    emitter,
                    new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "智能助手尚未完成配置"),
                    resolvedConversationId
            );
            return emitter;
        }

        sendEvent(emitter, "start", Map.of(
                "message", "开始生成回复",
                "conversationId", resolvedConversationId
        ));

        CompletableFuture.runAsync(() ->
                startSyntheticStream(
                        emitter,
                        resolvedConversationId,
                        question,
                        rentalAssistant
                )
        );
        return emitter;
    }

    private void startSyntheticStream(SseEmitter emitter,
                                      String conversationId,
                                      String question,
                                      RentalAssistant rentalAssistant) {
        try {
            String reply = invokeAssistantWithRecovery(rentalAssistant, conversationId, question);
            String formattedReply = formatReply(reply);
            for (String chunk : splitForStreaming(formattedReply)) {
                if (!StringUtils.hasText(chunk)) {
                    continue;
                }
                sendEvent(emitter, "delta", Map.of(
                        "content", chunk,
                        "conversationId", conversationId
                ));
                TimeUnit.MILLISECONDS.sleep(18);
            }

            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("conversationId", conversationId);
            payload.put("reply", formattedReply);
            payload.put("paragraphs", splitParagraphs(formattedReply));
            sendEvent(emitter, "complete", payload);
            emitter.complete();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitErrorAndComplete(emitter, e, conversationId);
        } catch (Exception e) {
            emitErrorAndComplete(emitter, e, conversationId);
        }
    }

    private void startTokenStream(SseEmitter emitter, String conversationId, TokenStream tokenStream) {
        StringBuilder contentBuilder = new StringBuilder();
        tokenStream
                .onPartialResponse(token -> {
                    if (!StringUtils.hasText(token)) {
                        return;
                    }
                    contentBuilder.append(token);
                    sendEvent(emitter, "delta", Map.of(
                            "content", token,
                            "conversationId", conversationId
                    ));
                })
                .beforeToolExecution(beforeToolExecution ->
                        sendEvent(emitter, "tool_call", buildToolCallEvent(beforeToolExecution, conversationId)))
                .onToolExecuted(toolExecution ->
                        sendEvent(emitter, "tool_result", buildToolResultEvent(toolExecution, conversationId)))
                .onCompleteResponse(chatResponse -> completeStream(emitter, conversationId, contentBuilder, chatResponse))
                .onError(error -> emitErrorAndComplete(emitter, error, conversationId));

        try {
            tokenStream.start();
        } catch (Exception e) {
            emitErrorAndComplete(emitter, e, conversationId);
        }
    }

    private void completeStream(SseEmitter emitter,
                                String conversationId,
                                StringBuilder contentBuilder,
                                ChatResponse chatResponse) {
        AiMessage aiMessage = chatResponse == null ? null : chatResponse.aiMessage();
        String reply = aiMessage == null ? null : aiMessage.text();
        if (!StringUtils.hasText(reply)) {
            reply = contentBuilder.toString();
        }
        String formattedReply = formatReply(reply);
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("reply", formattedReply);
        payload.put("paragraphs", splitParagraphs(formattedReply));
        sendEvent(emitter, "complete", payload);
        emitter.complete();
    }

    private void validateStreamReady() {
        if (!assistantProperties.isEnabled()) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "智能助手未启用");
        }
    }

    private long resolveStreamTimeoutMillis() {
        long timeout = assistantProperties.getTimeout() == null
                ? 60000L
                : assistantProperties.getTimeout().toMillis();
        return Math.max(timeout, 30000L) + 10000L;
    }

    private Map<String, Object> buildToolCallEvent(BeforeToolExecution beforeToolExecution, String conversationId) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        if (beforeToolExecution != null && beforeToolExecution.request() != null) {
            payload.put("toolName", beforeToolExecution.request().name());
            payload.put("toolArguments", beforeToolExecution.request().arguments());
        }
        return payload;
    }

    private Map<String, Object> buildToolResultEvent(ToolExecution toolExecution, String conversationId) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        if (toolExecution != null && toolExecution.request() != null) {
            payload.put("toolName", toolExecution.request().name());
            payload.put("toolArguments", toolExecution.request().arguments());
        }
        payload.put("toolResult", summarizeToolResult(toolExecution == null ? null : toolExecution.result()));
        return payload;
    }

    private String summarizeToolResult(String toolResult) {
        if (!StringUtils.hasText(toolResult)) {
            return "工具已执行完成";
        }
        String normalized = toolResult.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240) + "...";
    }

    private void emitErrorAndComplete(SseEmitter emitter, Throwable throwable, String conversationId) {
        log.warn("Assistant streaming failed", throwable);
        String message;
        if (throwable instanceof LeaseException leaseException) {
            message = leaseException.getMessage();
        } else {
            message = "智能助手调用失败，请稍后重试";
        }
        sendEvent(emitter, "error", Map.of(
                "message", message,
                "conversationId", resolveConversationId(conversationId)
        ));
        emitter.complete();
    }

    private String invokeAssistantWithRecovery(RentalAssistant rentalAssistant,
                                               String conversationId,
                                               String question) {
        try {
            return rentalAssistant.chat(conversationId, question);
        } catch (Exception firstException) {
            if (!isBrokenToolCallMemory(firstException) || !clearConversationMemory(conversationId)) {
                throw firstException;
            }

            log.warn("Assistant conversation memory was inconsistent and has been cleared, conversationId={}", conversationId, firstException);
            return rentalAssistant.chat(conversationId, question);
        }
    }

    private boolean clearConversationMemory(String conversationId) {
        AssistantMongoChatMemoryStore memoryStore = assistantChatMemoryStoreProvider.getIfAvailable();
        if (memoryStore == null || !StringUtils.hasText(conversationId)) {
            return false;
        }
        memoryStore.clearConversation(conversationId);
        return true;
    }

    private boolean isBrokenToolCallMemory(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (StringUtils.hasText(message)
                    && message.contains("tool_calls")
                    && (message.contains("tool_call_id") || message.contains("did not have response messages"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.debug("Failed to send SSE event: {}", eventName, e);
            throw new RuntimeException(e);
        }
    }

    private String normalizeQuestion(String message) {
        if (!StringUtils.hasText(message)) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }
        return message.trim();
    }

    private String resolveConversationId(String conversationId) {
        if (StringUtils.hasText(conversationId)) {
            return conversationId.trim();
        }
        return "lease-chat-" + UUID.randomUUID();
    }

    private String formatReply(String reply) {
        if (!StringUtils.hasText(reply)) {
            return DEFAULT_EMPTY_REPLY;
        }

        String normalized = reply
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceFirst("^```[a-zA-Z]*\\n?", "")
                .replaceFirst("\\n?```$", "")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .replaceAll("(?m)^\\s*[-*]\\s+", "• ");

        return normalized.trim();
    }

    private List<String> splitParagraphs(String reply) {
        return Arrays.stream(reply.split("\\n\\s*\\n"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private List<String> splitForStreaming(String reply) {
        if (!StringUtils.hasText(reply)) {
            return List.of(DEFAULT_EMPTY_REPLY);
        }

        int chunkSize = 24;
        List<String> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < reply.length(); i += chunkSize) {
            chunks.add(reply.substring(i, Math.min(reply.length(), i + chunkSize)));
        }
        return chunks;
    }
}
