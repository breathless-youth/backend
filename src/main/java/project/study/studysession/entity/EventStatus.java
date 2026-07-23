package project.study.studysession.entity;

/** 비공부 상태 이벤트 종류. 이 구간들을 제외한 나머지 세션 시간이 공부(집중) 시간이다. */
public enum EventStatus {
    READY,
    PHONE,
    AWAY
}
