package com.atguigu.lease.web.app.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Current task state for agent-like guidance")
public class AssistantTaskStateVo {

    @Schema(description = "Task type such as ROOM_SEARCH / ROOM_DETAIL / APPOINTMENT_QUERY")
    private String taskType;

    @Schema(description = "Task status such as WAITING_USER_INPUT / COMPLETED / NEEDS_REFINEMENT")
    private String taskStatus;

    @Schema(description = "Currently selected room ID if any")
    private Long selectedRoomId;

    @Schema(description = "Currently selected room title if any")
    private String selectedRoomTitle;

    @Schema(description = "Currently selected apartment ID if any")
    private Long selectedApartmentId;

    @Schema(description = "Pending appointment time waiting for confirmation")
    private String proposedAppointmentTime;

    @Schema(description = "Candidate rooms kept in the current conversation state")
    private List<AssistantRoomCandidateVo> candidateRooms;

    public AssistantTaskStateVo(String taskType,
                                String taskStatus,
                                Long selectedRoomId,
                                String selectedRoomTitle,
                                List<AssistantRoomCandidateVo> candidateRooms) {
        this(taskType, taskStatus, selectedRoomId, selectedRoomTitle, null, null, candidateRooms);
    }
}
