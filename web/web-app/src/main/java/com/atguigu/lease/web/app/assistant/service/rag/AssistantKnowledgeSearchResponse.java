package com.atguigu.lease.web.app.assistant.service.rag;

import java.util.Collections;
import java.util.List;

public class AssistantKnowledgeSearchResponse {

    private final boolean available;
    private final String message;
    private final List<AssistantKnowledgeSearchResult> results;

    public AssistantKnowledgeSearchResponse(boolean available,
                                           String message,
                                           List<AssistantKnowledgeSearchResult> results) {
        this.available = available;
        this.message = message;
        this.results = results == null ? Collections.emptyList() : results;
    }

    public boolean isAvailable() {
        return available;
    }

    public String getMessage() {
        return message;
    }

    public List<AssistantKnowledgeSearchResult> getResults() {
        return results;
    }
}
