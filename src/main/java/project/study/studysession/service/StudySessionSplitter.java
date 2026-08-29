package project.study.studysession.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySession;

/**
 * 세션을 KST 자정 경계로 분할하고, studySec/focusSec을 조각별로 배분하는 순수 로직 (BY-447, BY-471).
 *
 * <p>안 넘으면 조각 1개, 걸친 이벤트는 시각 기준으로 각 조각에 귀속된다. PAUSE는 총공부·순공 타이머를
 * 모두 멈추므로 두 배분 가중치에서 다 빠지고, 나머지 이벤트(PHONE/DEVICE/AWAY)는 순공 타이머만 멈추므로
 * focusSec 배분에서만 빠진다.
 */
final class StudySessionSplitter {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private StudySessionSplitter() {}

    /** 조각별 이벤트 클립과, studySec/focusSec 배분 가중치(studyActiveSec/focusActiveSec) 및 그 합계. */
    record SegmentWeights(
            List<List<StatusEvent>> segmentEvents,
            long[] studyActiveSecs,
            long[] focusActiveSecs,
            long totalStudyActiveSec,
            long totalFocusActiveSec) {}

    /** 조각 경계를 확정한다 (KST 자정 기준 — 24시간 한도 덕에 조각은 최대 2개). */
    static List<Instant> computeCuts(Instant startedAt, Instant endedAt) {
        List<Instant> cuts = new ArrayList<>();
        cuts.add(startedAt);
        for (Instant boundary = nextKstMidnight(startedAt);
                boundary.isBefore(endedAt);
                boundary = nextKstMidnight(boundary)) {
            cuts.add(boundary);
        }
        cuts.add(endedAt);
        return cuts;
    }

    /**
     * 검증·배분 기준은 저장되는 조각별 총시간(정수 초)의 합이다 — sub-second 타임스탬프가 자정에
     * 걸치면 0으로 나누기나 절삭 손실이 생길 수 있어서다. 조각 길이는 {@link #roundedSegmentSecs}로
     * 합이 원본과 일치하게 반올림한다.
     */
    static SegmentWeights computeSegmentWeights(List<Instant> cuts, List<StatusEvent> sorted) {
        int segmentCount = cuts.size() - 1;
        long[] segmentSecs = roundedSegmentSecs(cuts);
        List<List<StatusEvent>> segmentEvents = new ArrayList<>(segmentCount);
        long[] studyActiveSecs = new long[segmentCount];
        long[] focusActiveSecs = new long[segmentCount];
        long totalStudyActiveSec = 0;
        long totalFocusActiveSec = 0;
        for (int i = 0; i < segmentCount; i++) {
            long segmentSec = segmentSecs[i];
            List<StatusEvent> clipped = clip(sorted, cuts.get(i), cuts.get(i + 1));
            segmentEvents.add(clipped);
            long stopSec = sumDuration(clipped, EventStatus.PAUSE);
            long eventSec = sumDuration(clipped);
            studyActiveSecs[i] = segmentSec - stopSec;
            focusActiveSecs[i] = segmentSec - eventSec;
            totalStudyActiveSec += studyActiveSecs[i];
            totalFocusActiveSec += focusActiveSecs[i];
        }
        return new SegmentWeights(
                segmentEvents, studyActiveSecs, focusActiveSecs, totalStudyActiveSec, totalFocusActiveSec);
    }

    /**
     * 조각 길이(초)를 합이 원본 총시간과 일치하도록 배분한다 (largest-remainder, BY-471). 각 조각을 독립
     * 내림하면 자정 경계의 소수부가 조각마다 버려져 합이 원본보다 최대 (조각수-1)초 작아지므로, 버려진
     * 초를 소수부(nano)가 큰 조각부터 +1씩 돌려준다.
     */
    private static long[] roundedSegmentSecs(List<Instant> cuts) {
        int n = cuts.size() - 1;
        long[] base = new long[n];
        int[] fracNanos = new int[n];
        boolean[] bumped = new boolean[n];
        long baseSum = 0;
        for (int i = 0; i < n; i++) {
            Duration d = Duration.between(cuts.get(i), cuts.get(i + 1));
            base[i] = d.getSeconds();
            fracNanos[i] = d.getNano();
            baseSum += base[i];
        }
        long remainder = Duration.between(cuts.get(0), cuts.get(n)).getSeconds() - baseSum;
        for (long k = 0; k < remainder; k++) {
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (!bumped[i] && (best < 0 || fracNanos[i] > fracNanos[best])) {
                    best = i;
                }
            }
            base[best] += 1;
            bumped[best] = true;
        }
        return base;
    }

    /** 조각별 가중치대로 studySec/focusSec을 비례 배분해 세션들을 만든다 — 마지막 조각이 나머지를 가져가 합이 항상 요청값과 같다. */
    static List<StudySession> buildSessions(
            Long userId, List<Instant> cuts, SegmentWeights weights, int studySec, int focusSec) {
        int segmentCount = cuts.size() - 1;
        List<StudySession> sessions = new ArrayList<>();
        long allocatedStudySec = 0;
        long allocatedFocusSec = 0;
        for (int i = 0; i < segmentCount; i++) {
            long segmentStudySec;
            long segmentFocusSec;
            if (i == segmentCount - 1) {
                segmentStudySec = studySec - allocatedStudySec;
                segmentFocusSec = focusSec - allocatedFocusSec;
            } else {
                segmentStudySec =
                        studySec == 0 ? 0 : studySec * weights.studyActiveSecs()[i] / weights.totalStudyActiveSec();
                // focusActiveSec 합이 0(전 구간이 이벤트로 덮인 경우)이면 studyActiveSec 비율로 대체 배분
                boolean noFocusActiveTime = weights.totalFocusActiveSec() == 0;
                long focusWeight = noFocusActiveTime ? weights.studyActiveSecs()[i] : weights.focusActiveSecs()[i];
                long focusWeightTotal =
                        noFocusActiveTime ? weights.totalStudyActiveSec() : weights.totalFocusActiveSec();
                segmentFocusSec = focusSec == 0 ? 0 : focusSec * focusWeight / focusWeightTotal;
            }
            sessions.add(buildSession(
                    userId,
                    cuts.get(i),
                    cuts.get(i + 1),
                    (int) segmentStudySec,
                    (int) segmentFocusSec,
                    weights.segmentEvents().get(i)));
            allocatedStudySec += segmentStudySec;
            allocatedFocusSec += segmentFocusSec;
        }
        // 조각들이 원본 제출의 시작 시각을 루트로 공유해야 재제출 판별·응답 조회가 조각 단위로 어긋나지 않는다
        sessions.forEach(session -> session.attachToSubmission(cuts.get(0)));
        return sessions;
    }

    private static StudySession buildSession(
            Long userId, Instant startedAt, Instant endedAt, int studySec, int focusSec, List<StatusEvent> events) {
        LocalDate statDate = startedAt.atZone(KST).toLocalDate();
        return new StudySession(userId, statDate, startedAt, endedAt, studySec, focusSec, events);
    }

    private static long sumDuration(List<StatusEvent> events) {
        return events.stream()
                .mapToLong(
                        e -> Duration.between(e.getStartedAt(), e.getEndedAt()).toSeconds())
                .sum();
    }

    private static long sumDuration(List<StatusEvent> events, EventStatus status) {
        return events.stream()
                .filter(e -> e.getStatus() == status)
                .mapToLong(
                        e -> Duration.between(e.getStartedAt(), e.getEndedAt()).toSeconds())
                .sum();
    }

    private static Instant nextKstMidnight(Instant instant) {
        return instant.atZone(KST).toLocalDate().plusDays(1).atStartOfDay(KST).toInstant();
    }

    /** 이벤트들을 [segmentStart, segmentEnd) 구간으로 잘라낸다. 0초 조각은 버린다. */
    private static List<StatusEvent> clip(List<StatusEvent> sortedEvents, Instant segmentStart, Instant segmentEnd) {
        List<StatusEvent> clipped = new ArrayList<>();
        for (StatusEvent event : sortedEvents) {
            Instant start = event.getStartedAt().isAfter(segmentStart) ? event.getStartedAt() : segmentStart;
            Instant end = event.getEndedAt().isBefore(segmentEnd) ? event.getEndedAt() : segmentEnd;
            if (start.isBefore(end)) {
                // 조각 세션들이 같은 이벤트 엔티티를 공유하면 cascade 저장 시 한쪽이 행을 가져가므로 항상 새로 만든다
                clipped.add(new StatusEvent(event.getStatus(), start, end));
            }
        }
        return clipped;
    }
}
