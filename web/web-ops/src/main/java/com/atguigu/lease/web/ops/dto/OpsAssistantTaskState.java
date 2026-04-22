package com.atguigu.lease.web.ops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "助手任务状态")
public class OpsAssistantTaskState {

    @Schema(description = "任务类型")
    private String type;

    @Schema(description = "任务状态")
    private String status;
}
