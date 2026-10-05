package project.study.interview;

/**
 * 인터뷰 모집 대상 그룹 (BY-821 명세 §1, ADR-0027). 판정 기준은 {@code InterviewGroupService}가 갖는다.
 * 2번과 3번은 정의상 겹치지 않는다 — 마지막 세션이 168시간 전이면 최근 168시간 완료 세션은 0건이다.
 */
public enum InterviewGroup {
    /** 1번. 시작한 세션이 없는 사람. */
    G1_NOT_STARTED,
    /** 2번. 며칠 쓰다 168시간 넘게 돌아오지 않은 사람. */
    G2_LAPSED,
    /** 3번. 최근 168시간 완료 세션 3건 이상인 사람. */
    G3_ACTIVE,
    /** 어느 그룹에도 들지 않는 사람 — 안내를 띄우지 않는다. */
    NONE
}
