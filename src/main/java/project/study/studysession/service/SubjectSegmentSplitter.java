package project.study.studysession.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import project.study.studysession.dto.SubjectSegmentRequest;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySessionSubjectSegment;

/**
 * 과목 구간을 자정 조각으로 자르고, 그 조각의 비공부 이벤트로 과목별 총공부·순공을 계산하는 순수 로직 (ADR-0023).
 * 앱 타이머가 멈추는 규칙과 같다 — PAUSE는 총공부·순공 둘 다에서, 나머지 이벤트는 순공에서만 빠진다.
 * 이벤트끼리는 겹치지 않으므로(검증됨) 겹침 길이의 합이 곧 정확한 값이다.
 */
final class SubjectSegmentSplitter {

    private SubjectSegmentSplitter() {}

    /**
     * 정렬된 구간들을 [pieceStart, pieceEnd)로 잘라 조각 몫의 행을 만든다 — 절삭 뒤 길이가 0초인 조각은 행을 만들지 않는다.
     * 길이는 이벤트와 같이 초 단위 절삭이고, floor(a)+floor(b) ≤ floor(a+b)라 파생값이 음수가 되지 않는다(안전하게 0 하한).
     */
    static List<StudySessionSubjectSegment> clip(
            List<SubjectSegmentRequest> sorted, List<StatusEvent> pieceEvents, Instant pieceStart, Instant pieceEnd) {
        List<StudySessionSubjectSegment> clipped = new ArrayList<>();
        for (SubjectSegmentRequest segment : sorted) {
            Instant start = later(segment.startedAt(), pieceStart);
            Instant end = earlier(segment.endedAt(), pieceEnd);
            if (!start.isBefore(end)) {
                continue;
            }
            long length = Duration.between(start, end).toSeconds();
            if (length == 0) {
                continue;
            }
            long pauseOverlap = 0;
            long anyOverlap = 0;
            for (StatusEvent event : pieceEvents) {
                long overlap = overlapSec(start, end, event.getStartedAt(), event.getEndedAt());
                anyOverlap += overlap;
                if (event.getStatus() == EventStatus.PAUSE) {
                    pauseOverlap += overlap;
                }
            }
            int studySec = (int) Math.max(0, length - pauseOverlap);
            int focusSec = (int) Math.max(0, length - anyOverlap);
            clipped.add(new StudySessionSubjectSegment(segment.subjectId(), start, end, studySec, focusSec));
        }
        return clipped;
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
