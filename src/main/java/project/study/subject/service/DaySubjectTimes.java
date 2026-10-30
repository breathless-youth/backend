package project.study.subject.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import project.study.studysession.dto.SubjectTimeSum;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySession;
import project.study.studysession.entity.StudySessionSubjectSegment;

/**
 * 하루 창 [dayStart, dayEnd) 안의 과목별 총공부·순공 — 순수 로직 (ADR-0038).
 * 창에 통째로 든 구간은 저장된 파생값을 그대로 더하고, 창 경계(05시)에 걸친 구간만 잘라 그 세션의 이벤트로 다시 계산한다.
 * 계산 규칙은 저장 때(SubjectSegmentSplitter)와 앱 플래너와 같다 — PAUSE는 둘 다에서, 다른 이벤트는 순공에서만 빠진다.
 */
final class DaySubjectTimes {

    private DaySubjectTimes() {}

    static Map<Long, SubjectTimeSum> sum(List<StudySession> sessions, Instant dayStart, Instant dayEnd) {
        Map<Long, SubjectTimeSum> sums = new HashMap<>();
        for (StudySession session : sessions) {
            for (StudySessionSubjectSegment segment : session.getSubjectSegments()) {
                Instant start = later(segment.getStartedAt(), dayStart);
                Instant end = earlier(segment.getEndedAt(), dayEnd);
                if (!start.isBefore(end)) {
                    continue;
                }
                long studySec;
                long focusSec;
                if (start.equals(segment.getStartedAt()) && end.equals(segment.getEndedAt())) {
                    studySec = segment.getStudySec();
                    focusSec = segment.getFocusSec();
                } else {
                    long length = Duration.between(start, end).toSeconds();
                    long pauseOverlap = 0;
                    long anyOverlap = 0;
                    for (StatusEvent event : session.getEvents()) {
                        long overlap = overlapSec(start, end, event.getStartedAt(), event.getEndedAt());
                        anyOverlap += overlap;
                        if (event.getStatus() == EventStatus.PAUSE) {
                            pauseOverlap += overlap;
                        }
                    }
                    studySec = Math.max(0, length - pauseOverlap);
                    focusSec = Math.max(0, length - anyOverlap);
                }
                sums.merge(
                        segment.getSubjectId(),
                        new SubjectTimeSum(segment.getSubjectId(), studySec, focusSec),
                        (a, b) -> new SubjectTimeSum(a.id(), a.studySec() + b.studySec(), a.focusSec() + b.focusSec()));
            }
        }
        return sums;
    }

    private static long overlapSec(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        Instant start = later(aStart, bStart);
        Instant end = earlier(aEnd, bEnd);
        return start.isBefore(end) ? Duration.between(start, end).toSeconds() : 0;
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
