package project.study.studysession.entity;

/**
 * 비공부 상태 이벤트 종류. 이 구간들을 제외한 나머지 세션 시간이 공부(집중) 시간이다.
 * PAUSE(일시정지)는 총 공부시간 타이머도 함께 멈춘다 — 나머지는 순공시간 타이머만 멈춘다.
 * 자정 분할 배분 가중치 계산 시 이 구분이 반영된다(StudySessionService).
 */
public enum EventStatus {
    PHONE,
    DEVICE,
    AWAY,
    PAUSE
}
