package com.atguigu.lease.web.app.assistant.service.tool;

import com.atguigu.lease.web.app.service.LeaseOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AssistantLeaseOrderToolsTest {

    @Test
    void shouldRejectCreateLeaseOrderWithoutNaturalLanguageConfirmation() {
        LeaseOrderService leaseOrderService = mock(LeaseOrderService.class);
        AssistantLeaseOrderTools tools = new AssistantLeaseOrderTools(leaseOrderService);
        ToolContext toolContext = new ToolContext(Map.of(
                AssistantToolContextSupport.CURRENT_USER_ID, 8L,
                AssistantToolContextSupport.TOOL_EVENT_EMITTER, AssistantToolEventEmitter.noop()
        ));

        AssistantToolResult result = tools.createLeaseOrder(
                3L,
                2L,
                6L,
                "2026-04-21",
                "测试订单",
                false,
                toolContext
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("确认");
        verify(leaseOrderService, never()).submit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(8L));
    }
}
