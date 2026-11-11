package project.study.point.dto;

/** 포인트가 오간 사유. 3단계에서 충전이 더해진다. */
public enum PointReason {
    /** 측정한 공부 */
    STUDY,
    /** 공부 포인트의 날짜 옮김. 같은 금액을 한 날짜에서 빼고 다른 날짜에 더해 잔액은 그대로다. 포인트 기록 조회에는 나오지 않는다 */
    STUDY_ADJUST,
    /** 상점 구매 */
    PURCHASE,
    /** 출석 */
    ATTENDANCE,
    /** 하루 목표 달성 */
    DAILY_GOAL,
    /** 랭킹 메달. label은 "판 키/순위"다 (예: FOCUS_TIME:DAILY/1) */
    MEDAL,
    /** 광고 시청 보상 */
    AD
}
