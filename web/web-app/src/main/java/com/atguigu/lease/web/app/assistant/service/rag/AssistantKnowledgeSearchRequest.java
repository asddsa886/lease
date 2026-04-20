package com.atguigu.lease.web.app.assistant.service.rag;

public class AssistantKnowledgeSearchRequest {

    private final String question;
    private final Integer topK;

    public AssistantKnowledgeSearchRequest(String question, Integer topK) {
        this.question = question;
        this.topK = topK;
    }

    public static AssistantKnowledgeSearchRequest of(String question) {
        return new AssistantKnowledgeSearchRequest(question, null);
    }

    public String getQuestion() {
        return question;
    }

    public Integer getTopK() {
        return topK;
    }
}
