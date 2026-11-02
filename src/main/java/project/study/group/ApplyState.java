package project.study.group;

/** 공개 그룹 보기에서 내가 이 그룹에 할 수 있는 것 — 먼저 맞는 것 하나다 (MEMBER > APPLIED > FULL > UNAVAILABLE > CAN_APPLY). */
public enum ApplyState {
    /** 신청할 수 있다 */
    CAN_APPLY,
    /** 답을 기다리는 신청이 있다 */
    APPLIED,
    /** 이미 멤버다 */
    MEMBER,
    /** 멤버가 다 찼다 (6명) */
    FULL,
    /** 신청할 수 없다 — 이유는 응답의 unavailableReason */
    UNAVAILABLE
}
