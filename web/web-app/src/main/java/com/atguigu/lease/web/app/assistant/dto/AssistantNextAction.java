package com.atguigu.lease.web.app.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "AI助手建议操作")
public class AssistantNextAction {

    @Schema(description = "按钮文案")
    private String label;

    @Schema(description = "点击后自动填入的提示词")
    private String prompt;
}
