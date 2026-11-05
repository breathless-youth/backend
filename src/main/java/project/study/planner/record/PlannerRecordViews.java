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
        return new PlannerRecordView(
                key(bundle.getFirst()),
                bundle.getFirst().getSource(),
                later(from, bundle.getFirst().getStartedAt()),
                earlier(
                        to,
                        bundle.stream()
                                .map(PlannerRecordViews::end)
                                .max(Comparator.naturalOrder())
                                .orElseThrow()),
                study,
                focus,
                List.copyOf(segments));
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
        PlannerRecordMetrics.Times times = editedTimes(edit, originals, left, right, events);
        List<PlannerRecordSegment> segments = edit.subjectId() == null
                ? List.of()
                : List.of(new PlannerRecordSegment(edit.subjectId(), left, right, times.study(), times.focus()));
        return new PlannerRecordView(edit.key(), edit.source(), left, right, times.study(), times.focus(), segments);
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
