package project.study.group;

/** 멤버 책상의 상태 — 앱 전체 기준으로 이 순간 그 사람이 어디서 무엇을 하는지. */
public enum DeskStatus {
    /** 이 그룹 공부방에 있다 (카메라를 껐거나 휴식 중이어도 포함) */
    IN_ROOM,
    /** 다른 곳(개인 공부 등)에서 측정 중이다 */
    STUDYING,
    /** 다른 곳에서 측정을 일시정지했다 */
    PAUSED,
    /** 공부하지 않는다 */
    IDLE
}
