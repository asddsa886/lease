package com.atguigu.lease.web.ops.service.assistant;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.atguigu.lease.web.ops.config.OpsAssistantProperties;
import com.atguigu.lease.web.ops.dto.OpsAssistantChatRequest;
import com.atguigu.lease.web.ops.dto.OpsAssistantChatResponse;
import com.atguigu.lease.web.ops.dto.OpsAssistantStreamPayload;
import com.atguigu.lease.web.ops.dto.OpsAssistantTaskState;
import com.atguigu.lease.web.ops.dto.OpsLogScanReport;
import com.atguigu.lease.web.ops.exception.OpsAssistantUnavailableException;
import com.atguigu.lease.web.ops.service.log.OpsLogScanService;
import com.atguigu.lease.web.ops.service.session.OpsAssistantSessionService;
import com.atguigu.lease.web.ops.service.tool.OpsToolEventContext;
import com.atguigu.lease.web.ops.service.tool.OpsToolEventEmitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.util.json.JsonParser;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class MultiAgentOpsAssistantService implements OpsAssistantService {

    private static final String ASSISTANT_UNAVAILABLE_MESSAGE = "运维助手当前不可用，请检查 Spring AI ChatModel 配置或模型服务状态。";
    private static final String EMPTY_ANALYSIS_MESSAGE = "当前没有拿到有效分析结果，你可以继续追问，或先触发一次最近窗口扫描。";

    private final SupervisorAgent supervisorAgent;
    private final OpsAssistantProperties assistantProperties;
    private final OpsLogScanService logScanService;
    private final OpsAssistantSessionService sessionService;

    public MultiAgentOpsAssistantService(SupervisorAgent supervisorAgent,
                                         OpsAssistantProperties assistantProperties,
                                         OpsLogScanService logScanService,
                                         OpsAssistantSessionService sessionService) {
        this.supervisorAgent = supervisorAgent;
        this.assistantProperties = assistantProperties;
        this.logScanService = logScanService;
        this.sessionService = sessionService;
    }

    @Override
    public OpsAssistantChatResponse chat(OpsAssistantChatRequest request) {
        String conversationId = resolveConversationId(request);
        String reply = execute(conversationId, request.getMessage(), OpsToolEventEmitter.noop());
        rememberSession(conversationId, request.getMessage(), reply);
        return OpsAssistantChatResponse.builder()
                .conversationId(conversationId)
                .reply(reply)
                .taskState(OpsAssistantTaskState.builder()
                        .type(OpsAssistantConstants.TASK_TYPE)
                        .status("completed")
                        .build())
                .build();
    }

    @Override
    public SseEmitter streamChat(OpsAssistantChatRequest request) {
        String conversationId = resolveConversationId(request);
        SseEmitter emitter = new SseEmitter(assistantProperties.getStreamTimeout().toMillis());
        AtomicBoolean closed = new AtomicBoolean(false);

        CompletableFuture.runAsync(() -> {
            try {
                sendEvent(emitter, "start", OpsAssistantStreamPayload.builder()
                        .conversationId(conversationId)
                        .message("运维助手已开始分析日志。")
                        .build(), closed);
                String reply = execute(conversationId, request.getMessage(), (eventName, toolName, message) ->
                        sendEvent(emitter, eventName, OpsAssistantStreamPayload.builder()
                                .conversationId(conversationId)
                                .toolName(toolName)
                                .message(message)
                                .build(), closed));
                rememberSession(conversationId, request.getMessage(), reply);
                for (String chunk : splitReply(reply, 80)) {
                    sendEvent(emitter, "delta", OpsAssistantStreamPayload.builder()
                            .conversationId(conversationId)
                            .content(chunk)
                            .build(), closed);
                }
                sendEvent(emitter, "complete", OpsAssistantStreamPayload.builder()
                        .conversationId(conversationId)
                        .reply(reply)
                        .taskState(OpsAssistantTaskState.builder()
                                .type(OpsAssistantConstants.TASK_TYPE)
                                .status("completed")
                                .build())
                        .build(), closed);
            } catch (Exception e) {
                log.error("Ops assistant stream failed", e);
                sendEvent(emitter, "error", OpsAssistantStreamPayload.builder()
                        .conversationId(conversationId)
                        .message(resolveUnavailableMessage(e))
                        .taskState(OpsAssistantTaskState.builder()
                                .type(OpsAssistantConstants.TASK_TYPE)
                                .status("failed")
                                .build())
                        .build(), closed);
            } finally {
                safeComplete(emitter, closed);
            }
        });

        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));
        return emitter;
    }

    private String execute(String conversationId, String userMessage, OpsToolEventEmitter emitter) {
        OpsToolEventContext.bind(emitter);
        try {
            Optional<OverAllState> state = runAgent(buildPrompt(conversationId, userMessage));
            return extractReply(state).orElseGet(this::buildDefaultReply);
        } catch (Exception e) {
            throw asUnavailableException(e);
        } finally {
            OpsToolEventContext.clear();
        }
    }

    private Optional<OverAllState> runAgent(String prompt) {
        try {
            return supervisorAgent.invoke(prompt);
        } catch (GraphRunnerException e) {
            throw new OpsAssistantUnavailableException(ASSISTANT_UNAVAILABLE_MESSAGE, e);
        }
    }

    private Optional<String> extractReply(Optional<OverAllState> stateOptional) {
        if (stateOptional == null || stateOptional.isEmpty()) {
            return Optional.empty();
        }
        OverAllState state = stateOptional.get();
        return extractReplyFromOutputKey(state)
                .or(() -> extractReplyFromMessages(state));
    }

    private Optional<String> extractReplyFromOutputKey(OverAllState state) {
        Object value = state.value(OpsAssistantConstants.SPECIALIST_REPLY_KEY).orElse(null);
        if (value instanceof AssistantMessage assistantMessage && StringUtils.hasText(assistantMessage.getText())) {
            return Optional.of(assistantMessage.getText().trim());
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return Optional.of(text.trim());
        }
        return Optional.empty();
    }

    private Optional<String> extractReplyFromMessages(OverAllState state) {
        Object value = state.value("messages").orElse(null);
        if (!(value instanceof List<?> messages) || messages.isEmpty()) {
            return Optional.empty();
        }
        return messages.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .map(AssistantMessage::getText)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(text -> !isRoutingDirective(text))
                .reduce((first, second) -> second);
    }

    private boolean isRoutingDirective(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String trimmed = text.trim();
        if (OpsAssistantConstants.isRoutingToken(trimmed)) {
            return true;
        }
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return false;
        }
        try {
            Object parsed = JsonParser.fromJson(trimmed, List.class);
            if (!(parsed instanceof List<?> items) || items.isEmpty()) {
                return false;
            }
            return items.stream()
                    .map(item -> item == null ? null : String.valueOf(item).trim())
                    .allMatch(OpsAssistantConstants::isRoutingToken);
        } catch (Exception e) {
            return false;
        }
    }

    private String buildPrompt(String conversationId, String userMessage) {
        OpsLogScanReport latestReport = logScanService.getLatestReport();
        String contextPrompt = sessionService.buildContextPrompt(conversationId);
        StringBuilder builder = new StringBuilder();
        builder.append("会话ID：").append(conversationId).append(System.lineSeparator());
        if (StringUtils.hasText(contextPrompt)) {
            builder.append(contextPrompt).append(System.lineSeparator());
        }
        if (latestReport != null) {
            builder.append("当前扫描概况：").append(System.lineSeparator())
                    .append("- 当前状态：").append(latestReport.getStatus()).append(System.lineSeparator())
                    .append("- 当前扫描ID：").append(latestReport.getScanTaskId()).append(System.lineSeparator())
                    .append("- 当前摘要：").append(latestReport.getSummary()).append(System.lineSeparator());
        }
        builder.append("用户问题：").append(userMessage);
        return builder.toString();
    }

    private void rememberSession(String conversationId, String userMessage, String assistantReply) {
        OpsLogScanReport latestReport = logScanService.getLatestReport();
        sessionService.remember(
                conversationId,
                userMessage,
                assistantReply,
                latestReport == null ? null : latestReport.getScanTaskId(),
                detectCategory(userMessage, assistantReply)
        );
    }

    private String detectCategory(String userMessage, String assistantReply) {
        String combinedText = ((userMessage == null ? "" : userMessage) + " " + (assistantReply == null ? "" : assistantReply))
                .toLowerCase();
        if (combinedText.contains("redis")
                || combinedText.contains("rabbitmq")
                || combinedText.contains("mysql")
                || combinedText.contains("minio")
                || combinedText.contains("milvus")
                || combinedText.contains("依赖")) {
            return "INFRA";
        }
        if (combinedText.contains("sql")
                || combinedText.contains("慢")
                || combinedText.contains("hikari")
                || combinedText.contains("性能")
                || combinedText.contains("costms")) {
            return "PERFORMANCE_DB";
        }
        if (combinedText.contains("异常")
                || combinedText.contains("启动")
                || combinedText.contains("oom")
                || combinedText.contains("bean")) {
            return "APP";
        }
        return null;
    }

    private String buildDefaultReply() {
        OpsLogScanReport latestReport = logScanService.getLatestReport();
        if (latestReport == null) {
            return EMPTY_ANALYSIS_MESSAGE;
        }
        return """
                当前还没有拿到专项分析结论。
                你可以直接让我：
                - 分析当前扫描
                - 查最近 3 次故障
                - 看看是不是 Redis / MySQL / RabbitMQ 问题
                - 看看有没有慢 SQL 或高耗时请求

                当前最新扫描摘要：%s
                """.formatted(latestReport.getSummary());
    }

    private List<String> splitReply(String reply, int chunkSize) {
        if (!StringUtils.hasText(reply)) {
            return List.of(EMPTY_ANALYSIS_MESSAGE);
        }
        if (reply.length() <= chunkSize) {
            return List.of(reply);
        }
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < reply.length(); start += chunkSize) {
            chunks.add(reply.substring(start, Math.min(reply.length(), start + chunkSize)));
        }
        return chunks;
    }

    private boolean sendEvent(SseEmitter emitter, String eventName, Object payload, AtomicBoolean closed) {
        if (closed.get()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
            return true;
        } catch (IOException e) {
            closed.set(true);
            return false;
        }
    }

    private void safeComplete(SseEmitter emitter, AtomicBoolean closed) {
        if (closed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    private String resolveConversationId(OpsAssistantChatRequest request) {
        if (request != null && StringUtils.hasText(request.getConversationId())) {
            return request.getConversationId().trim();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private OpsAssistantUnavailableException asUnavailableException(Exception exception) {
        if (exception instanceof OpsAssistantUnavailableException unavailableException) {
            return unavailableException;
        }
        log.error("Ops assistant chat failed", exception);
        return new OpsAssistantUnavailableException(ASSISTANT_UNAVAILABLE_MESSAGE, exception);
    }

    private String resolveUnavailableMessage(Exception exception) {
        if (exception instanceof OpsAssistantUnavailableException unavailableException
                && StringUtils.hasText(unavailableException.getMessage())) {
            return unavailableException.getMessage();
        }
        return ASSISTANT_UNAVAILABLE_MESSAGE;
    }
}
