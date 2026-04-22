package com.atguigu.lease.web.ops.service.session;

import com.atguigu.lease.web.ops.config.OpsAssistantProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class OpsAssistantSessionServiceTest {

    @Test
    void shouldRememberConversationContext() {
        OpsAssistantProperties properties = new OpsAssistantProperties();
        properties.setSessionTtl(Duration.ofHours(1));
        OpsAssistantSessionService sessionService = new OpsAssistantSessionService(properties);

        sessionService.remember("conv-1", "刚才为什么挂了", "更像是 Redis 连接失败", 3001L, "INFRA");

        String contextPrompt = sessionService.buildContextPrompt("conv-1");
        assertThat(contextPrompt).contains("3001");
        assertThat(contextPrompt).contains("INFRA");
        assertThat(contextPrompt).contains("刚才为什么挂了");
        assertThat(contextPrompt).contains("Redis 连接失败");
    }

    @Test
    void shouldExpireOldSessionState() {
        OpsAssistantProperties properties = new OpsAssistantProperties();
        properties.setSessionTtl(Duration.ofMinutes(1));
        OpsAssistantSessionService sessionService = new OpsAssistantSessionService(properties);

        OpsAssistantSessionState state = sessionService.getOrCreate("conv-expired");
        state.setCurrentScanId(9999L);
        state.setFocusCategory("APP");
        state.setLastAccessedAt(new Date(System.currentTimeMillis() - Duration.ofMinutes(5).toMillis()));

        OpsAssistantSessionState refreshed = sessionService.getOrCreate("conv-expired");

        assertThat(refreshed.getCurrentScanId()).isNull();
        assertThat(refreshed.getFocusCategory()).isNull();
        assertThat(refreshed.getRecentTurns()).isEmpty();
    }
}
