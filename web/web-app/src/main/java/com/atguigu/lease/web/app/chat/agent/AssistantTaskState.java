package com.atguigu.lease.web.app.chat.agent;

import java.util.List;

public record AssistantTaskState(
        String taskType,
        String taskStatus,
        Long selectedRoomId,
        String selectedRoomTitle,
        List<RoomCandidate> candidateRooms
) {

    public record RoomCandidate(Long roomId, String title) {
    }
}
