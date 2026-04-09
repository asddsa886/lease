package com.atguigu.lease.web.app.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
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

    @Schema(description = "Currently selected appointment ID if any")
    private Long selectedAppointmentId;

    @Schema(description = "Currently selected appointment label if any")
    private String selectedAppointmentLabel;

    @Schema(description = "Pending appointment time waiting for confirmation")
    private String proposedAppointmentTime;

    @Schema(description = "Candidate rooms kept in the current conversation state")
    private List<AssistantRoomCandidateVo> candidateRooms;

    public AssistantTaskStateVo(String taskType,
                                String taskStatus,
                                Long selectedRoomId,
                                String selectedRoomTitle,
                                Long selectedApartmentId,
                                Long selectedAppointmentId,
                                String selectedAppointmentLabel,
                                String proposedAppointmentTime,
                                List<AssistantRoomCandidateVo> candidateRooms) {
        this.taskType = taskType;
        this.taskStatus = taskStatus;
        this.selectedRoomId = selectedRoomId;
        this.selectedRoomTitle = selectedRoomTitle;
        this.selectedApartmentId = selectedApartmentId;
        this.selectedAppointmentId = selectedAppointmentId;
        this.selectedAppointmentLabel = selectedAppointmentLabel;
        this.proposedAppointmentTime = proposedAppointmentTime;
        this.candidateRooms = candidateRooms;
    }

    public AssistantTaskStateVo(String taskType,
                                String taskStatus,
                                Long selectedRoomId,
                                String selectedRoomTitle,
                                List<AssistantRoomCandidateVo> candidateRooms) {
        this(taskType, taskStatus, selectedRoomId, selectedRoomTitle, null, null, null, null, candidateRooms);
    }

    public AssistantTaskStateVo(String taskType,
                                String taskStatus,
                                Long selectedRoomId,
                                String selectedRoomTitle,
                                Long selectedApartmentId,
                                String proposedAppointmentTime,
                                List<AssistantRoomCandidateVo> candidateRooms) {
        this(taskType, taskStatus, selectedRoomId, selectedRoomTitle, selectedApartmentId, null, null, proposedAppointmentTime, candidateRooms);
    }
}
