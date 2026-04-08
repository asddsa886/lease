package com.atguigu.lease.web.app.chat.service;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.web.app.chat.config.AssistantProperties;
import com.atguigu.lease.web.app.chat.dto.AssistantChatResponseVo;
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
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantChatService {

    private static final String DEFAULT_EMPTY_REPLY = "我当前没有生成有效回复，请换个问法再试。";

    private final AssistantProperties assistantProperties;
    private final ObjectProvider<RentalAssistant> rentalAssistantProvider;
    private final ObjectProvider<StreamingRentalAssistant> streamingRentalAssistantProvider;

    public AssistantChatResponseVo chat(String message) {
        String question = normalizeQuestion(message);
        if (!assistantProperties.isEnabled()) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "智能助手未启用");
        }

        RentalAssistant rentalAssistant = rentalAssistantProvider.getIfAvailable();
        if (rentalAssistant == null) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "智能助手尚未完成配置");
        }

        try {
            String reply = rentalAssistant.chat(question);
            String formattedReply = formatReply(reply);
            return new AssistantChatResponseVo(formattedReply, splitParagraphs(formattedReply));
        } catch (LeaseException e) {
            throw e;
        } catch (Exception e) {
            log.error("Assistant chat failed", e);
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "智能助手调用失败，请稍后重试");
        }
    }

    public SseEmitter stream(String message) {
        SseEmitter emitter = new SseEmitter(resolveStreamTimeoutMillis());
        String question;
        try {
            question = normalizeQuestion(message);
            validateStreamReady();
        } catch (Exception e) {
            emitErrorAndComplete(emitter, e);
            return emitter;
        }

        StreamingRentalAssistant streamingRentalAssistant = streamingRentalAssistantProvider.getIfAvailable();
        if (streamingRentalAssistant == null) {
            emitErrorAndComplete(emitter, new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "智能助手尚未完成配置"));
            return emitter;
        }

        sendEvent(emitter, "start", Map.of("message", "开始生成回复"));

        CompletableFuture.runAsync(() -> startTokenStream(emitter, streamingRentalAssistant.chat(question)));
        return emitter;
    }

    private void startTokenStream(SseEmitter emitter, TokenStream tokenStream) {
        StringBuilder contentBuilder = new StringBuilder();
        tokenStream
                .onPartialResponse(token -> {
                    if (!StringUtils.hasText(token)) {
                        return;
                    }
                    contentBuilder.append(token);
                    sendEvent(emitter, "delta", Map.of("content", token));
                })
                .beforeToolExecution(beforeToolExecution ->
                        sendEvent(emitter, "tool_call", buildToolCallEvent(beforeToolExecution)))
                .onToolExecuted(toolExecution ->
                        sendEvent(emitter, "tool_result", buildToolResultEvent(toolExecution)))
                .onCompleteResponse(chatResponse -> completeStream(emitter, contentBuilder, chatResponse))
                .onError(error -> emitErrorAndComplete(emitter, error));

        try {
            tokenStream.start();
        } catch (Exception e) {
            emitErrorAndComplete(emitter, e);
        }
    }

    private void completeStream(SseEmitter emitter, StringBuilder contentBuilder, ChatResponse chatResponse) {
        AiMessage aiMessage = chatResponse == null ? null : chatResponse.aiMessage();
        String reply = aiMessage == null ? null : aiMessage.text();
        if (!StringUtils.hasText(reply)) {
            reply = contentBuilder.toString();
        }
        String formattedReply = formatReply(reply);
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
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

    private Map<String, Object> buildToolCallEvent(BeforeToolExecution beforeToolExecution) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        if (beforeToolExecution != null && beforeToolExecution.request() != null) {
            payload.put("toolName", beforeToolExecution.request().name());
            payload.put("toolArguments", beforeToolExecution.request().arguments());
        }
        return payload;
    }

    private Map<String, Object> buildToolResultEvent(ToolExecution toolExecution) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
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

    private void emitErrorAndComplete(SseEmitter emitter, Throwable throwable) {
        log.warn("Assistant streaming failed", throwable);
        String message;
        if (throwable instanceof LeaseException leaseException) {
            message = leaseException.getMessage();
        } else {
            message = "智能助手调用失败，请稍后重试";
        }
        sendEvent(emitter, "error", Map.of("message", message));
        emitter.complete();
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
}
