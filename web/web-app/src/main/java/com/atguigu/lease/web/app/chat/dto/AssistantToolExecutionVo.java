package com.atguigu.lease.web.app.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "工具执行摘要")
public class AssistantToolExecutionVo {

    @Schema(description = "工具名称")
    private String toolName;

    @Schema(description = "工具参数 JSON")
    private String toolArguments;

    @Schema(description = "工具是否执行失败")
    private Boolean failed;

    @Schema(description = "工具执行结果摘要")
    private String resultSummary;
}
