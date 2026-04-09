package com.atguigu.lease.web.app.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Suggested next action for the current task")
public class AssistantNextActionVo {

    @Schema(description = "Stable action code")
    private String action;

    @Schema(description = "Short label for UI")
    private String label;

    @Schema(description = "Suggested follow-up prompt")
    private String prompt;

    @Schema(description = "Related room ID if any")
    private Long roomId;
}
