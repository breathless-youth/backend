package project.study.studysession;

/**
 * ADR-0009가 정한 임계값을 한 곳에 모은다 — 앱 화면의 스트릭 판정과 지표 집계가 같은 잣대를 쓰도록
 * 보장하기 위함.
 */
public final class StudySessionThresholds {

    /** 1분 — 조회에 보이는 최소 순공시간. */
    public static final int MIN_LIST_FOCUS_SEC = 60;

    /** 10분 — 스트릭 인정 최소 순공시간(세션 단위). */
    public static final int MIN_STREAK_FOCUS_SEC = 600;

    private StudySessionThresholds() {}
}
