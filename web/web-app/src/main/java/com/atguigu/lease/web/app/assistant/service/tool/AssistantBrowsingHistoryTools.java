package com.atguigu.lease.web.app.assistant.service.tool;

import com.atguigu.lease.web.app.service.BrowsingHistoryService;
import com.atguigu.lease.web.app.vo.history.HistoryItemVo;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AssistantBrowsingHistoryTools extends AbstractAssistantTools {

    private final BrowsingHistoryService browsingHistoryService;

    public AssistantBrowsingHistoryTools(BrowsingHistoryService browsingHistoryService) {
        this.browsingHistoryService = browsingHistoryService;
    }

    @Tool(description = "List browsing history of current user.")
    public AssistantToolResult listMyBrowsingHistory(@ToolParam(description = "Page number, default 1") Integer pageNumber,
                                                     @ToolParam(description = "Page size, default 10") Integer pageSize,
                                                     ToolContext toolContext) {
        return executeTool("listMyBrowsingHistory", toolContext, "浏览记录查询成功", () -> {
            IPage<HistoryItemVo> page = browsingHistoryService.pageItem(
                    new Page<>(safePageNumber(pageNumber), safePageSize(pageSize)),
                    currentUserId(toolContext)
            );
            return AssistantPageResult.from(page);
        });
    }
}
