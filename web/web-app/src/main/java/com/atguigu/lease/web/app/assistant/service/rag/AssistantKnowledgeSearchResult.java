package com.atguigu.lease.web.app.assistant.service.rag;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class AssistantKnowledgeSearchResult {

    private String id;

    private String title;

    private String content;

    private String snippet;

    private float score;

    private String metadata;
}
