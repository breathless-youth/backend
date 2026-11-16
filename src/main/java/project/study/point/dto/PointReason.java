package project.study.point.dto;

/** 포인트가 오간 사유. */
public enum PointReason {
    /** 측정한 공부 */
    STUDY,
    /** 공부 포인트의 날짜 옮김. 같은 금액을 한 날짜에서 빼고 다른 날짜에 더해 잔액은 그대로다. 포인트 기록 조회에는 나오지 않는다 */
    STUDY_ADJUST,
    /** 상점 구매 */
    PURCHASE,
    /** 포인트로 마스코트 사기. label은 마스코트 코드다 */
    MASCOT_PURCHASE,
    /** 시작 미션 보상. label은 "미션 버전/단계"다 (예: home-v3/1) */
    MISSION,
    /** 운영이 그냥 준 포인트. 심사 계정의 처음 상태에 쓴다 (BY-1300) */
    GRANT,
    /** 출석 */
    ATTENDANCE,
    /** 하루 목표 달성 */
    DAILY_GOAL,
    /** 랭킹 메달. label은 "판 키/순위"다 (예: FOCUS_TIME:DAILY/1) */
    MEDAL,
    /** 광고 시청 보상 */
    AD,
    /** 인앱결제로 충전. label은 스토어 상품 ID다 */
    CHARGE,
    /** 결제 환불로 되돌림. label은 스토어 상품 ID다 */
    REFUND
}
