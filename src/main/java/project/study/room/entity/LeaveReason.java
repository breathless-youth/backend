package project.study.room.entity;

public enum LeaveReason {
    EXPLICIT,
    DISCONNECT_TIMEOUT,
    SWITCHED_ROOM,
    // 그룹 공부방 — 그룹에서 내보내짐 / 그룹을 나갔거나 그룹이 사라짐 (BY-1124)
    KICKED,
    GROUP_LEFT
}
