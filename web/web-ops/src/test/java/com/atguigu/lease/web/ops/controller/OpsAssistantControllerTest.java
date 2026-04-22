package com.atguigu.lease.web.ops.controller;

import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.web.ops.exception.OpsAssistantUnavailableException;
import com.atguigu.lease.web.ops.service.assistant.OpsAssistantService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OpsAssistantControllerTest {

    @Test
    void shouldReturn503WhenAssistantChatFails() throws Exception {
        OpsAssistantService assistantService = mock(OpsAssistantService.class);
        when(assistantService.chat(any()))
                .thenThrow(new OpsAssistantUnavailableException("运维助手当前不可用，请检查 Spring AI ChatModel 配置或模型服务状态"));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new OpsAssistantController(assistantService))
                .setControllerAdvice(new OpsAssistantExceptionHandler())
                .build();

        mockMvc.perform(post("/ops/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "为什么刚才挂了"
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ResultCodeEnum.SERVICE_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("运维助手当前不可用，请检查 Spring AI ChatModel 配置或模型服务状态"));
    }
}
