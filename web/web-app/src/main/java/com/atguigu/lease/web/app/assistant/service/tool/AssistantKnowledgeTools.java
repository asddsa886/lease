package com.atguigu.lease.web.app.assistant.service.tool;

import com.atguigu.lease.web.app.assistant.service.rag.AssistantKnowledgeSearchRequest;
import com.atguigu.lease.web.app.assistant.service.rag.AssistantKnowledgeSearchService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AssistantKnowledgeTools extends AbstractAssistantTools {

    private final AssistantKnowledgeSearchService knowledgeSearchService;

    public AssistantKnowledgeTools(AssistantKnowledgeSearchService knowledgeSearchService) {
        this.knowledgeSearchService = knowledgeSearchService;
    }

    @Tool(description = "查询租房知识库，适合回答预约规则、签约流程、入住须知、押金支付、平台FAQ等说明性问题，不适合查询实时预约列表或实时订单列表")
    public AssistantToolResult searchKnowledge(@ToolParam(description = "需要检索的租房知识问题", required = true) String question,
                                               ToolContext toolContext) {
        return executeTool("searchKnowledge", toolContext, "租房知识检索完成",
                () -> knowledgeSearchService.search(AssistantKnowledgeSearchRequest.of(question)));
    }
}
