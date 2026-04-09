package com.atguigu.lease.web.app.chat.agent;

import java.util.List;

public record AssistantTaskState(
        String taskType,
        String taskStatus,
        Long selectedRoomId,
        String selectedRoomTitle,
        Long selectedApartmentId,
        Long selectedAppointmentId,
        String selectedAppointmentLabel,
        String proposedAppointmentTime,
        List<RoomCandidate> candidateRooms
) {

    public AssistantTaskState(String taskType,
                              String taskStatus,
                              Long selectedRoomId,
                              String selectedRoomTitle,
                              Long selectedApartmentId,
                              String proposedAppointmentTime,
                              List<RoomCandidate> candidateRooms) {
        this(
                taskType,
                taskStatus,
                selectedRoomId,
                selectedRoomTitle,
                selectedApartmentId,
                null,
                null,
                proposedAppointmentTime,
                candidateRooms
        );
    }

    public record RoomCandidate(Long roomId, String title) {
    }
}
