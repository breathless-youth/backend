package project.study.room.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

// disconnected: 소켓이 끊겨 30초 유예 중인 멤버 (FE가 "재접속 중"으로 표시하고 후속 재대조 시점을 잡는 데 쓴다)
// micOn·onBreak·owner: 그룹 공부방에만 있는 값(BY-1124, websocket.html §11.2). 초대코드 방은 셋 다 null이고 키 자체를 싣지 않는다 —
// 옛 앱이 보는 초대코드 방 멤버 객체는 그대로다. 기존 nullable 필드(goal 등)는 지금처럼 null로 싣는다
public record RoomMember(
        Long userId,
        String nickname,
        String goal,
        String category,
        String profileImageUrl,
        boolean cameraOn,
        String focusState,
        int focusSec,
        boolean disconnected,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean micOn,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean onBreak,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean owner) {}
