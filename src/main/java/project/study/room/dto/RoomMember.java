package project.study.room.dto;

// disconnected: 소켓이 끊겨 30초 유예 중인 멤버 (FE가 "재접속 중"으로 표시하고 후속 재대조 시점을 잡는 데 쓴다)
public record RoomMember(
        Long userId,
        String nickname,
        String goal,
        String category,
        boolean cameraOn,
        String focusState,
        int focusSec,
        boolean disconnected) {}
