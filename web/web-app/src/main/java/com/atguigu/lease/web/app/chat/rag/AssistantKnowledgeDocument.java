package com.atguigu.lease.web.app.chat.rag;

import java.util.List;

public record AssistantKnowledgeDocument(String title, List<String> keywords, String content) {
}
