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
@Schema(description = "AI助手任务状态")
public class AssistantTaskState {

    @Schema(description = "任务类型")
    private String taskType;

    @Schema(description = "任务状态")
    private String taskStatus;
}
