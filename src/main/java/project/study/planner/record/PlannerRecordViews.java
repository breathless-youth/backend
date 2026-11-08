package project.study.planner.record;

import static project.study.planner.record.PlannerRecordMetrics.earlier;
import static project.study.planner.record.PlannerRecordMetrics.later;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySession;
import project.study.studysession.entity.StudySessionSource;
import project.study.studysession.entity.StudySessionSubjectSegment;

final class PlannerRecordViews {
    private PlannerRecordViews() {}

    static String key(StudySession session) {
        String prefix = session.isManual() ? "m:" : "c:";
        return prefix
                + (session.getSubmissionStartedAt() == null
                        ? "id:" + session.getId()
                        : session.getSubmissionStartedAt());
    }

    static Instant end(StudySession session) {
        return session.getEndedAt() == null
                ? session.getStartedAt().plusSeconds(Math.max(0, value(session.getStudySec())))
                : session.getEndedAt();
    }

    static PlannerRecordView original(List<StudySession> bundle, Instant from, Instant to) {
        long study = 0;
        long focus = 0;
        List<PlannerRecordSegment> segments = new ArrayList<>();
        for (StudySession session : bundle) {
            Instant left = later(from, session.getStartedAt());
            Instant right = earlier(to, end(session));
            if (!left.isBefore(right)) {
                continue;
            }
            study += PlannerRecordMetrics.share(
                    value(session.getStudySec()),
                    session.getStartedAt(),
                    end(session),
                    left,
                    right,
                    session.getEvents(),
                    true);
            focus += PlannerRecordMetrics.share(
                    value(session.getFocusSec()),
                    session.getStartedAt(),
                    end(session),
                    left,
                    right,
                    session.getEvents(),
                    false);
            for (StudySessionSubjectSegment segment : session.getSubjectSegments()) {
                addSegment(segments, segment, session.getEvents(), left, right);
            }
        }
        Instant fullStart = bundle.getFirst().getStartedAt();
        Instant fullEnd = bundle.stream()
                .map(PlannerRecordViews::end)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        return new PlannerRecordView(
                key(bundle.getFirst()),
                bundle.getFirst().getSource(),
                false,
                later(from, fullStart),
                earlier(to, fullEnd),
                fullStart,
                fullEnd,
                study,
                focus,
                List.copyOf(segments),
                0,
                PlannerRecordPieces.clip(PlannerRecordPieces.original(bundle), from, to));
    }

    private static void addSegment(
            List<PlannerRecordSegment> segments,
            StudySessionSubjectSegment segment,
            List<StatusEvent> events,
            Instant from,
            Instant to) {
        Instant left = later(from, segment.getStartedAt());
        Instant right = earlier(to, segment.getEndedAt());
        if (left.isBefore(right)) {
            segments.add(new PlannerRecordSegment(
                    segment.getSubjectId(),
                    left,
                    right,
                    PlannerRecordMetrics.share(
                            value(segment.getStudySec()),
                            segment.getStartedAt(),
                            segment.getEndedAt(),
                            left,
                            right,
                            events,
                            true),
                    PlannerRecordMetrics.share(
                            value(segment.getFocusSec()),
                            segment.getStartedAt(),
                            segment.getEndedAt(),
                            left,
                            right,
                            events,
                            false)));
        }
    }

    static PlannerRecordView edited(PlannerRecordEdit edit, List<StudySession> originals, Instant from, Instant to) {
        Instant left = later(from, edit.startedAt());
        Instant right = earlier(to, edit.endedAt());
        List<StatusEvent> events = edit.source() == StudySessionSource.MANUAL
                ? List.of()
                : originals.stream().flatMap(s -> s.getEvents().stream()).toList();
        if (edit.pieces() != null) {
            return byPieces(edit, originals, left, right, events);
        }
        PlannerRecordMetrics.Times times = editedTimes(edit, originals, left, right, events);
        List<PlannerRecordSegment> segments = editedSegments(edit, originals, left, right, times);
        return new PlannerRecordView(
                edit.key(),
                edit.source(),
                true,
                left,
                right,
                edit.startedAt(),
                edit.endedAt(),
                times.study(),
                times.focus(),
                segments,
                edit.revision(),
                PlannerRecordPieces.clip(PlannerRecordPieces.legacy(edit, originals), left, right));
    }

    /**
     * 분 범위 수정본. 맞닿은 조각은 이어진 구간 시작부터의 누적값 차이로 나눈다. 조각마다 초를 따로 버리면
     * 과목만 칠해도 합계가 달라지기 때문이다. 시각을 옮기지 않은 기록은 원래 제출값을 이벤트 위치로 배분하므로
     * 과목 변경은 합계를 바꾸지 않고 지운 조각의 몫만 빠진다. 시각을 옮긴 기록은 기존처럼 이벤트로 다시 센다.
     */
    private static PlannerRecordView byPieces(
            PlannerRecordEdit edit,
            List<StudySession> originals,
            Instant left,
            Instant right,
            List<StatusEvent> events) {
        List<PlannerRecordPiece> pieces = PlannerRecordPieces.clip(edit.pieces(), left, right);
        long study = 0;
        long focus = 0;
        List<PlannerRecordSegment> segments = new ArrayList<>();
        int i = 0;
        while (i < pieces.size()) {
            int end = i + 1;
            while (end < pieces.size()
                    && pieces.get(end).startedAt().equals(pieces.get(end - 1).endedAt())) {
                end++;
            }
            List<PlannerRecordPiece> run = pieces.subList(i, end);
            List<PlannerRecordMetrics.Times> shares = runShares(edit, originals, run, events);
            for (int k = 0; k < run.size(); k++) {
                PlannerRecordPiece piece = run.get(k);
                PlannerRecordMetrics.Times times = shares.get(k);
                study += times.study();
                focus += times.focus();
                if (piece.subjectId() != null) {
                    segments.add(new PlannerRecordSegment(
                            piece.subjectId(), piece.startedAt(), piece.endedAt(), times.study(), times.focus()));
                }
            }
            i = end;
        }
        return new PlannerRecordView(
                edit.key(),
                edit.source(),
                true,
                pieces.isEmpty() ? left : pieces.getFirst().startedAt(),
                pieces.isEmpty() ? right : pieces.getLast().endedAt(),
                edit.startedAt(),
                edit.endedAt(),
                study,
                focus,
                List.copyOf(segments),
                edit.revision(),
                pieces);
    }

    /**
     * 이어진 조각들의 몫. 초 절삭 때문에 누적값이 잠깐 줄어들 수 있어 누적값을 단조 증가하게 맞춘 뒤 차이를 낸다.
     * 마지막 누적값은 구간 전체 값과 같아 합계는 그대로이고 어떤 조각도 음수가 되지 않는다.
     */
    private static List<PlannerRecordMetrics.Times> runShares(
            PlannerRecordEdit edit,
            List<StudySession> originals,
            List<PlannerRecordPiece> run,
            List<StatusEvent> events) {
        Instant start = run.getFirst().startedAt();
        PlannerRecordMetrics.Times total =
                cumulative(edit, originals, start, run.getLast().endedAt(), events);
        List<PlannerRecordMetrics.Times> shares = new ArrayList<>();
        long study = 0;
        long focus = 0;
        for (PlannerRecordPiece piece : run) {
            PlannerRecordMetrics.Times upTo = cumulative(edit, originals, start, piece.endedAt(), events);
            long nextStudy = Math.min(Math.max(study, upTo.study()), total.study());
            long nextFocus = Math.min(Math.max(focus, upTo.focus()), total.focus());
            shares.add(new PlannerRecordMetrics.Times(nextStudy - study, nextFocus - focus));
            study = nextStudy;
            focus = nextFocus;
        }
        return shares;
    }

    private static PlannerRecordMetrics.Times cumulative(
            PlannerRecordEdit edit, List<StudySession> originals, Instant from, Instant to, List<StatusEvent> events) {
        return edit.preserveOriginalTotals()
                ? originalTimes(originals, from, to)
                : PlannerRecordMetrics.recompute(from, to, events);
    }

    private static PlannerRecordMetrics.Times originalTimes(List<StudySession> originals, Instant from, Instant to) {
        if (originals.isEmpty()) {
            return new PlannerRecordMetrics.Times(0, 0);
        }
        PlannerRecordView view = original(originals, from, to);
        return new PlannerRecordMetrics.Times(view.studySec(), view.focusSec());
    }

    private static List<PlannerRecordSegment> editedSegments(
            PlannerRecordEdit edit,
            List<StudySession> originals,
            Instant from,
            Instant to,
            PlannerRecordMetrics.Times times) {
        if (edit.subjectId() == null) {
            return List.of();
        }
        if (edit.source() != StudySessionSource.MANUAL || !edit.preserveOriginalTotals()) {
            return List.of(new PlannerRecordSegment(edit.subjectId(), from, to, times.study(), times.focus()));
        }
        List<PlannerRecordSegment> segments = new ArrayList<>();
        // 다른 기록을 피해 저장된 수동 조각 사이의 빈 구간까지 새 과목으로 칠하지 않는다.
        for (StudySession session : originals) {
            Instant left = later(from, session.getStartedAt());
            Instant right = earlier(to, end(session));
            if (left.isBefore(right)) {
                PlannerRecordView piece = original(List.of(session), left, right);
                segments.add(
                        new PlannerRecordSegment(edit.subjectId(), left, right, piece.studySec(), piece.focusSec()));
            }
        }
        return List.copyOf(segments);
    }

    private static PlannerRecordMetrics.Times editedTimes(
            PlannerRecordEdit edit,
            List<StudySession> originals,
            Instant left,
            Instant right,
            List<StatusEvent> events) {
        if (edit.preserveOriginalTotals()) {
            PlannerRecordView original = original(originals, left, right);
            return new PlannerRecordMetrics.Times(original.studySec(), original.focusSec());
        }
        return PlannerRecordMetrics.recompute(left, right, events);
    }

    private static long value(Integer value) {
        return value == null ? 0 : value;
    }
}
