package com.atguigu.lease.web.ops.service.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpsAssistantSessionState {

    private String conversationId;

    private Long currentScanId;

    private String focusCategory;

    private Date createdAt;

    private Date lastAccessedAt;

    @Builder.Default
    private List<ConversationTurn> recentTurns = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConversationTurn {

        private String role;

        private String content;

        private Date time;
    }
}
