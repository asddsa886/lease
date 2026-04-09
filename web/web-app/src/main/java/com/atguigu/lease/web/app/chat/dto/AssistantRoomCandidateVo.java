package com.atguigu.lease.web.app.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Candidate room in the current task context")
public class AssistantRoomCandidateVo {

    @Schema(description = "Room ID")
    private Long roomId;

    @Schema(description = "Readable room title")
    private String title;
}
