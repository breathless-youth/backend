package project.study.studysession.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import project.study.studysession.entity.StatusEvent;

/** 세션 제출 검증 규칙 모음 — 규칙 위반은 InvalidSessionException(400). 검증은 자정 분할 전의 원본 제출 기준이다. */
final class StudySessionValidator {

    private static final Duration MAX_DURATION = Duration.ofHours(24);
    private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofMinutes(5);

    private StudySessionValidator() {}

    static void validatePeriod(Instant startedAt, Instant endedAt, Instant now) {
        if (!endedAt.isAfter(startedAt)) {
            throw new InvalidSessionException("세션 종료 시각은 시작 시각 이후여야 합니다");
        }
        if (Duration.between(startedAt, endedAt).compareTo(MAX_DURATION) > 0) {
            throw new InvalidSessionException("세션은 24시간을 초과할 수 없습니다");
        }
        if (endedAt.isAfter(now.plus(CLOCK_SKEW_TOLERANCE))) {
            throw new InvalidSessionException("세션 종료 시각이 미래일 수 없습니다");
        }
    }

    /** studySec 상한은 방 체류시간이 아니라 PAUSE(일시정지) 시간을 제외한 시간이다 — PAUSE 중엔 총공부 타이머도 멈춘다. */
    static void validateStudySec(int studySec, long totalStudyActiveSec) {
        if (studySec < 0 || studySec > totalStudyActiveSec) {
            throw new InvalidSessionException("총 공부 시간은 0 이상, 일시정지를 제외한 세션 시간 이하여야 합니다");
        }
    }

    /** focusSec 상한은 이벤트 총합이 아니라 studySec이다 — 이벤트로 focusSec을 역산·제한하지 않는다(ADR-0006). */
    static void validateFocusSec(int focusSec, int studySec) {
        if (focusSec < 0 || focusSec > studySec) {
            throw new InvalidSessionException("순공 시간은 0 이상, 총 공부 시간 이하여야 합니다");
        }
    }

    static void validateEvents(Instant startedAt, Instant endedAt, List<StatusEvent> sortedEvents) {
        StatusEvent previous = null;
        for (StatusEvent event : sortedEvents) {
            if (!event.getEndedAt().isAfter(event.getStartedAt())) {
                throw new InvalidSessionException("이벤트 종료 시각은 시작 시각 이후여야 합니다");
            }
            if (event.getStartedAt().isBefore(startedAt) || event.getEndedAt().isAfter(endedAt)) {
                throw new InvalidSessionException("이벤트는 세션 구간 안에 있어야 합니다");
            }
            if (previous != null && event.getStartedAt().isBefore(previous.getEndedAt())) {
                throw new InvalidSessionException("이벤트 구간이 서로 겹칠 수 없습니다");
            }
            previous = event;
        }
    }
}
