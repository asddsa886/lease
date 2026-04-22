package com.atguigu.lease.web.ops.service.assistant;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.atguigu.lease.web.ops.config.OpsAssistantProperties;
import com.atguigu.lease.web.ops.controller.OpsAssistantController;
import com.atguigu.lease.web.ops.dto.OpsAssistantChatRequest;
import com.atguigu.lease.web.ops.dto.OpsAssistantChatResponse;
import com.atguigu.lease.web.ops.dto.OpsLogScanReport;
import com.atguigu.lease.web.ops.exception.OpsAssistantUnavailableException;
import com.atguigu.lease.web.ops.service.log.OpsLogScanService;
import com.atguigu.lease.web.ops.service.session.OpsAssistantSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MultiAgentOpsAssistantServiceTest {

    @Test
    void shouldReturnReplyFromSpecialistOutputKey() throws Exception {
        MultiAgentOpsAssistantService service = createServiceWithState(buildStateWithOutputKey("Redis 连接失败，请先检查容器和端口。"));
        OpsAssistantChatRequest request = new OpsAssistantChatRequest();
        request.setMessage("为什么刚才挂了");

        OpsAssistantChatResponse response = service.chat(request);

        assertThat(response.getReply()).isEqualTo("Redis 连接失败，请先检查容器和端口。");
        assertThat(response.getTaskState().getStatus()).isEqualTo("completed");
    }

    @Test
    void shouldIgnoreRoutingMessagesWhenReadingFallbackMessages() throws Exception {
        MultiAgentOpsAssistantService service = createServiceWithState(buildStateWithMessages(
                "[\"ops-infra-agent\"]",
                "Redis 连接建立超时，先检查 Redis 容器状态和网络连通性。",
                "[\"FINISH\"]"
        ));
        OpsAssistantChatRequest request = new OpsAssistantChatRequest();
        request.setMessage("最近是不是 Redis 老出问题");

        OpsAssistantChatResponse response = service.chat(request);

        assertThat(response.getReply()).isEqualTo("Redis 连接建立超时，先检查 Redis 容器状态和网络连通性。");
    }

    @Test
    void shouldThrowUnavailableExceptionWhenSyncChatFails() throws Exception {
        MultiAgentOpsAssistantService service = createServiceWithFailingAgent();
        OpsAssistantChatRequest request = new OpsAssistantChatRequest();
        request.setMessage("why did it crash");

        assertThatThrownBy(() -> service.chat(request))
                .isInstanceOf(OpsAssistantUnavailableException.class)
                .hasMessageContaining("Spring AI ChatModel");
    }

    @Test
    void shouldEmitErrorEventWhenStreamChatFails() throws Exception {
        MultiAgentOpsAssistantService service = createServiceWithFailingAgent();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new OpsAssistantController(service)).build();

        MvcResult mvcResult = mockMvc.perform(post("/ops/assistant/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "why did it crash"
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:start")))
                .andExpect(content().string(containsString("event:error")))
                .andExpect(content().string(containsString("\"status\":\"failed\"")))
                .andExpect(content().string(containsString("\"type\":\"ops-assistant\"")));
    }

    private MultiAgentOpsAssistantService createServiceWithFailingAgent() throws Exception {
        SupervisorAgent supervisorAgent = mock(SupervisorAgent.class);
        when(supervisorAgent.invoke(anyString())).thenThrow(new GraphRunnerException("boom"));
        return createService(supervisorAgent);
    }

    private MultiAgentOpsAssistantService createServiceWithState(OverAllState state) throws Exception {
        SupervisorAgent supervisorAgent = mock(SupervisorAgent.class);
        when(supervisorAgent.invoke(anyString())).thenReturn(Optional.of(state));
        return createService(supervisorAgent);
    }

    private MultiAgentOpsAssistantService createService(SupervisorAgent supervisorAgent) {
        OpsAssistantProperties properties = new OpsAssistantProperties();
        properties.setStreamTimeout(Duration.ofSeconds(5));

        OpsLogScanService logScanService = mock(OpsLogScanService.class);
        when(logScanService.getLatestReport()).thenReturn(buildLatestReport());

        OpsAssistantSessionService sessionService = mock(OpsAssistantSessionService.class);
        when(sessionService.buildContextPrompt(anyString())).thenReturn("");

        return new MultiAgentOpsAssistantService(supervisorAgent, properties, logScanService, sessionService);
    }

    private OverAllState buildStateWithOutputKey(String reply) {
        return new OverAllState(new HashMap<>(java.util.Map.of(
                OpsAssistantConstants.SPECIALIST_REPLY_KEY, new AssistantMessage(reply)
        )));
    }

    private OverAllState buildStateWithMessages(String... messages) {
        List<AssistantMessage> assistantMessages = java.util.Arrays.stream(messages)
                .map(AssistantMessage::new)
                .toList();
        return new OverAllState(new HashMap<>(java.util.Map.of("messages", assistantMessages)));
    }

    private OpsLogScanReport buildLatestReport() {
        OpsLogScanReport report = new OpsLogScanReport();
        report.setScanTaskId(1001L);
        report.setStatus("SUCCESS");
        report.setSummary("latest scan complete");
        return report;
    }
}
