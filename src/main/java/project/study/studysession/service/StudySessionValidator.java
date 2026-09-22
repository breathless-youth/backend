package project.study.studysession.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import project.study.studysession.dto.SubjectSegmentRequest;
import project.study.studysession.entity.StatusEvent;

/** 세션 제출 검증 규칙 모음 — 규칙 위반은 InvalidSessionException(400). 검증은 자정 분할 전의 원본 제출 기준이다. */
final class StudySessionValidator {

    private static final Duration MAX_DURATION = Duration.ofHours(24);
    private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofMinutes(5);

    private StudySessionValidator() {}

    /**
     * 기간 조회의 from/to 짝·순서를 검증한다 — periodStats(메인·비교)와 streak 공용.
     * <ul>
     *   <li>둘 다 null: 기간 미지정으로 통과(기간이 선택인 streak·비교 구간용)</li>
     *   <li>한쪽만 null: 짝이 안 맞아 거절</li>
     *   <li>둘 다 지정: from&le;to</li>
     * </ul>
     * 범위 상한은 여기서 보지 않는다 — streak은 전체 이력을 훑어 3년 연속 공부처럼 넓은 범위가 정상이고,
     * 실제 공부한 날만 쿼리로 돌려줘 자원 부담이 없다. 넓은 범위가 문제되는 순회형 조회(periodStats)만
     * {@link #validateMaxRangeDays}를 따로 건다. 메인 period의 from/to 필수는 @RequestParam이 먼저 보장한다.
     */
    static void validateDateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            if ((from == null) != (to == null)) {
                throw new InvalidSessionException("from과 to는 함께 지정해야 합니다");
            }
            return;
        }
        if (from.isAfter(to)) {
            throw new InvalidSessionException("from은 to보다 이후일 수 없습니다");
        }
    }

    /**
     * 순회형 기간 조회(periodStats)의 범위 상한 방어 — [from, to]를 하루도 빠짐없이 배열로 채우므로,
     * 넓은 범위 요청이 서버 자원을 무한정 쓰지 못하게 maxDays로 막는다. from/to는 둘 다 지정된 상태를 전제한다.
     */
    static void validateMaxRangeDays(LocalDate from, LocalDate to, int maxDays) {
        if (ChronoUnit.DAYS.between(from, to) > maxDays) {
            throw new InvalidSessionException("조회 범위가 너무 넓습니다 (최대 " + maxDays + "일)");
        }
    }

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

    /**
     * 과목 구간(ADR-0023) — 이벤트와 같은 규칙: 종료 > 시작, 세션 구간 안, 서로 겹치지 않음(맞닿음 허용). 정렬된 목록을 전제한다.
     * 구간 합을 세션 studySec에 묶지 않는다(ADR-0021 §4). 소유 검증은 세션 도메인 밖(StudySubjectService.assertOwned)에서 한다.
     */
    static void validateSubjectSegments(
            Instant startedAt, Instant endedAt, List<SubjectSegmentRequest> sortedSegments) {
        SubjectSegmentRequest previous = null;
        for (SubjectSegmentRequest segment : sortedSegments) {
            if (!segment.endedAt().isAfter(segment.startedAt())) {
                throw new InvalidSessionException("과목 구간 종료 시각은 시작 시각 이후여야 합니다");
            }
            if (segment.startedAt().isBefore(startedAt) || segment.endedAt().isAfter(endedAt)) {
                throw new InvalidSessionException("과목 구간은 세션 구간 안에 있어야 합니다");
            }
            if (previous != null && segment.startedAt().isBefore(previous.endedAt())) {
                throw new InvalidSessionException("과목 구간이 서로 겹칠 수 없습니다");
            }
            previous = segment;
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
