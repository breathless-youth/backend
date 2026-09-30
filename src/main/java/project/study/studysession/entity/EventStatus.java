package project.study.studysession.entity;

/**
 * 비공부 상태 이벤트 종류. 이 구간들을 제외한 나머지 세션 시간이 공부(집중) 시간이다.
 * PAUSE(일시정지)는 총 공부시간 타이머도 함께 멈춘다 — 나머지는 순공시간 타이머만 멈춘다.
 * 자정 분할 배분 가중치 계산 시 이 구분이 반영된다(StudySessionService).
 *
 * <p>선언 순서가 그대로 eventCounts(EnumMap) 응답의 키 순서가 된다 — 성격이 같은 SLEEP을 AWAY 옆에 두고,
 * 혼자 총공부 타이머까지 멈추는 PAUSE를 맨 뒤에 남겨 둔다. 값 추가에 마이그레이션은 필요 없다
 * (status는 varchar이고 CHECK 제약이 없다 — V6 참고).
 */
public enum EventStatus {
    PHONE,
    DEVICE,
    AWAY,
    /** 졸음 — 판정은 단말이 하고(눈 감김·엎드림) 서버는 결과만 받는다. 계산상 AWAY와 완전히 같다 (BY-706). */
    SLEEP,
    PAUSE
}
