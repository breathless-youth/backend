package project.study.planner.record;

import static project.study.studysession.StudySessionThresholds.MIN_LIST_FOCUS_SEC;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySession;
import project.study.studysession.entity.StudySessionSource;

/** 날짜별 투영. 원본 조각을 보존해 과목 없는 옛 수동 묶음의 빈 구간도 채우지 않는다. */
final class PersonalRecordPieces {
    private PersonalRecordPieces() {}

    static List<PersonalRecordPiece> original(List<StudySession> bundle, PlannerRecordEdit edit) {
        List<PersonalRecordPiece> pieces = new ArrayList<>();
        for (StudySession session : bundle) {
            Instant start = session.getStartedAt();
            Instant end = PlannerRecordViews.end(session);
            PlannerRecordView view = edit == null
                    ? PlannerRecordViews.original(bundle, start, end)
                    : PlannerRecordViews.edited(edit, bundle, start, end);
            pieces.add(new PersonalRecordPiece(
                    "s:" + session.getId(),
                    session.getId(),
                    session.getStatDate(),
                    ZoneId.of(session.getTimeZone()),
                    view,
                    clip(session.getEvents(), start, end),
                    Set.copyOf(session.getCompletedTaskIds()),
                    session.getEndedAt() != null,
                    submission(bundle, start)));
        }
        return pieces;
    }

    static List<PersonalRecordPiece> changed(PlannerRecordEdit edit, List<StudySession> bundle, ZoneId requestedZone) {
        ZoneId zone =
                bundle.isEmpty() ? requestedZone : ZoneId.of(bundle.getFirst().getTimeZone());
        List<StatusEvent> events = edit.source() == StudySessionSource.MANUAL
                ? List.of()
                : bundle.stream().flatMap(s -> s.getEvents().stream()).toList();
        Set<Long> tasks =
                bundle.stream().flatMap(s -> s.getCompletedTaskIds().stream()).collect(Collectors.toSet());
        List<PersonalRecordPiece> pieces = new ArrayList<>();
        Instant start = edit.startedAt();
        boolean tasksAttached = false;
        while (start.isBefore(edit.endedAt())) {
            LocalDate date = start.atZone(zone).toLocalDate();
            Instant end = PlannerRecordMetrics.earlier(
                    edit.endedAt(), date.plusDays(1).atStartOfDay(zone).toInstant());
            PlannerRecordView view = PlannerRecordViews.edited(edit, bundle, start, end);
            Long originalId = bundle.isEmpty() ? null : bundle.getFirst().getId();
            pieces.add(new PersonalRecordPiece(
                    edit.key() + "@" + date,
                    originalId,
                    date,
                    zone,
                    view,
                    clip(events, start, end),
                    !tasksAttached && view.focusSec() >= MIN_LIST_FOCUS_SEC ? tasks : Set.of(),
                    true,
                    submission(bundle, edit.startedAt())));
            tasksAttached |= view.focusSec() >= MIN_LIST_FOCUS_SEC;
            start = end;
        }
        return pieces;
    }

    private static Instant submission(List<StudySession> bundle, Instant fallback) {
        if (bundle.isEmpty()) return fallback;
        StudySession first = bundle.getFirst();
        return first.getSubmissionStartedAt() == null ? first.getStartedAt() : first.getSubmissionStartedAt();
    }

    static List<StatusEvent> clip(List<StatusEvent> events, Instant start, Instant end) {
        List<StatusEvent> clipped = new ArrayList<>();
        for (StatusEvent event : events) {
            if (event.getStartedAt() == null || event.getEndedAt() == null) continue;
            Instant left = PlannerRecordMetrics.later(start, event.getStartedAt());
            Instant right = PlannerRecordMetrics.earlier(end, event.getEndedAt());
            if (left.isBefore(right)) clipped.add(new StatusEvent(event.getStatus(), left, right));
        }
        return List.copyOf(clipped);
    }
}
