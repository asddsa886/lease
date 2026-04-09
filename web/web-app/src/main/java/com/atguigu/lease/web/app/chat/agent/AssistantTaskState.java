package com.atguigu.lease.web.app.chat.agent;

import java.util.List;

public record AssistantTaskState(
        String taskType,
        String taskStatus,
        Long selectedRoomId,
        String selectedRoomTitle,
        Long selectedApartmentId,
        String proposedAppointmentTime,
        List<RoomCandidate> candidateRooms
) {

    public record RoomCandidate(Long roomId, String title) {
    }
}
