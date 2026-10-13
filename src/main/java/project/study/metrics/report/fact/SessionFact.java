package project.study.metrics.report.fact;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 세션 한 건 — studysession 도메인이 채운다. 자정 분할 조각은 각각 한 건이다(ADR-0012 결정 3).
 *
 * @param pauseCount PAUSE 상태 이벤트 수
 * @param social 세션 구간이 그 유저의 방 참여 구간과 겹치는지
 */
public record SessionFact(
        long userId, LocalDate statDate, Instant startedAt, int focusSec, int pauseCount, boolean social) {}
