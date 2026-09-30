package project.study.room.websocket;

/**
 * 서버 → 클라이언트 STOMP 메시지의 {@code type} 값. 와이어 포맷은 {@link #name()} 문자열 그대로다.
 * 여기에 값을 더하면 docs/websocket.html에도 실어야 한다 — WebSocketDocsContractTest가 대조한다 (BY-667).
 */
public enum RoomMessageType {
    SNAPSHOT,
    MEMBER_JOINED,
    MEMBER_LEFT,
    CAMERA_CHANGED,
    FOCUS_CHANGED,
    STUDY_TIME,
    SIGNAL,
    ROOM_UNAVAILABLE
}
