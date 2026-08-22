package project.study.room.dto;

public record RoomMember(
        Long userId,
        String nickname,
        String goal,
        String category,
        boolean cameraOn,
        String focusState,
        int studySeconds) {}
