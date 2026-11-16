package project.study.shop.dto;

/** 보상 상품을 받는 조건의 종류. */
public enum UnlockType {
    /** 연속 공부 일수. 지금 이어 가는 일수가 아니라 최고 기록으로 견준다 */
    STREAK_DAYS,
    /** 측정한 누적 순공 시간 */
    TOTAL_FOCUS_HOURS
}
