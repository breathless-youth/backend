package project.study.metrics.report.fact;

import java.time.Instant;

/**
 * 세션 한 건 — studysession 도메인이 채운다. 자정 분할 조각은 각각 한 건이다(ADR-0012 결정 3).
 * 리포트 날짜는 시작 시각의 KST 날짜다 — stat_date는 사용자 시간대 날짜라 쓰지 않는다(ADR-0042).
 *
 * @param pauseCount PAUSE 상태 이벤트 수
 * @param social 세션 구간이 그 유저의 방 참여 구간과 겹치는지
 */
public record SessionFact(long userId, Instant startedAt, int focusSec, int pauseCount, boolean social) {}
