package com.atguigu.lease.web.ops.service.assistant;

import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.atguigu.lease.web.ops.config.OpsAssistantProperties;
import com.atguigu.lease.web.ops.dto.OpsAssistantChatRequest;
import com.atguigu.lease.web.ops.dto.OpsAssistantChatResponse;
import com.atguigu.lease.web.ops.dto.OpsAssistantStreamPayload;
import com.atguigu.lease.web.ops.dto.OpsAssistantTaskState;
import com.atguigu.lease.web.ops.dto.OpsLogScanReport;
import com.atguigu.lease.web.ops.service.log.OpsLogScanService;
import com.atguigu.lease.web.ops.service.session.OpsAssistantSessionService;
import com.atguigu.lease.web.ops.service.tool.OpsToolEventContext;
import com.atguigu.lease.web.ops.service.tool.OpsToolEventEmitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class MultiAgentOpsAssistantService implements OpsAssistantService {

    private static final String TASK_TYPE = "ops-assistant";

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
        try {
            String reply = execute(conversationId, request.getMessage(), OpsToolEventEmitter.noop());
            rememberSession(conversationId, request.getMessage(), reply);
            return OpsAssistantChatResponse.builder()
                    .conversationId(conversationId)
                    .reply(reply)
                    .taskState(OpsAssistantTaskState.builder().type(TASK_TYPE).status("completed").build())
                    .build();
        } catch (Exception e) {
            log.error("Ops assistant chat failed", e);
            return OpsAssistantChatResponse.builder()
                    .conversationId(conversationId)
                    .reply("运维助手处理失败，请先检查模型配置，或直接调用 /ops/log-scan/latest 查看当前扫描结果。")
                    .taskState(OpsAssistantTaskState.builder().type(TASK_TYPE).status("failed").build())
                    .build();
        }
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
                        .message("运维助手已开始分析日志")
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
                        .taskState(OpsAssistantTaskState.builder().type(TASK_TYPE).status("completed").build())
                        .build(), closed);
            } catch (Exception e) {
                log.error("Ops assistant stream failed", e);
                sendEvent(emitter, "error", OpsAssistantStreamPayload.builder()
                        .conversationId(conversationId)
                        .message("运维助手处理失败，请检查模型配置或当前日志目录设置。")
                        .taskState(OpsAssistantTaskState.builder().type(TASK_TYPE).status("failed").build())
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
            List<Message> messages = runAgent(buildPrompt(conversationId, userMessage));
            return extractReply(messages);
        } finally {
            OpsToolEventContext.clear();
        }
    }

    private List<Message> runAgent(String prompt) {
        try {
            return supervisorAgent.streamMessages(prompt).collectList().block();
        } catch (GraphRunnerException e) {
            throw new IllegalStateException("运维助手执行失败", e);
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
        builder.append("当前扫描概况：").append(System.lineSeparator())
                .append("- 当前状态：").append(latestReport.getStatus()).append(System.lineSeparator())
                .append("- 当前扫描ID：").append(latestReport.getScanTaskId()).append(System.lineSeparator())
                .append("- 当前摘要：").append(latestReport.getSummary()).append(System.lineSeparator())
                .append("用户问题：").append(userMessage);
        return builder.toString();
    }

    private void rememberSession(String conversationId, String userMessage, String assistantReply) {
        OpsLogScanReport latestReport = logScanService.getLatestReport();
        sessionService.remember(
                conversationId,
                userMessage,
                assistantReply,
                latestReport.getScanTaskId(),
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

    private String extractReply(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "当前没有拿到有效分析结果，你可以继续追问，或者先触发一次最近窗口扫描。";
        }
        return messages.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .map(AssistantMessage::getText)
                .filter(text -> text != null && !text.isBlank())
                .reduce((first, second) -> second)
                .orElse("当前没有拿到有效分析结果，你可以继续追问，或者先触发一次最近窗口扫描。");
    }

    private List<String> splitReply(String reply, int chunkSize) {
        if (reply == null || reply.isBlank()) {
            return List.of("当前没有拿到有效分析结果。");
        }
        if (reply.length() <= chunkSize) {
            return List.of(reply);
        }
        java.util.ArrayList<String> chunks = new java.util.ArrayList<>();
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
}
