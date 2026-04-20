package com.atguigu.lease.web.app.assistant.service.tool;

import com.atguigu.lease.web.app.service.ApartmentInfoService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AssistantApartmentTools extends AbstractAssistantTools {

    private final ApartmentInfoService apartmentInfoService;

    public AssistantApartmentTools(ApartmentInfoService apartmentInfoService) {
        this.apartmentInfoService = apartmentInfoService;
    }

    @Tool(description = "查询公寓详情")
    public AssistantToolResult getApartmentDetail(@ToolParam(description = "公寓ID", required = true) Long apartmentId,
                                                  ToolContext toolContext) {
        return executeTool("getApartmentDetail", toolContext, "公寓详情查询成功",
                () -> apartmentInfoService.getDetailById(apartmentId));
    }
}
