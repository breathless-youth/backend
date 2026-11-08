package project.study.planner.record;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;

final class PersonalRecordStatistics {
    private PersonalRecordStatistics() {}

    static double rate(long focus, long study) {
        return study <= 0 ? 0 : Math.round(focus * 1000.0 / study) / 10.0;
    }

    static Map<EventStatus, Long> counts(List<StatusEvent> events) {
        Map<EventStatus, Long> counts = new EnumMap<>(EventStatus.class);
        for (EventStatus status : EventStatus.values()) counts.put(status, 0L);
        events.forEach(event -> counts.merge(event.getStatus(), 1L, Long::sum));
        return counts;
    }

    static long longest(PersonalRecordPiece piece) {
        Instant cursor = piece.view().startedAt();
        long longest = 0;
        for (StatusEvent event : piece.events().stream()
                .sorted(Comparator.comparing(StatusEvent::getStartedAt))
                .toList()) {
            longest = Math.max(longest, PlannerRecordMetrics.seconds(cursor, event.getStartedAt()));
            cursor = PlannerRecordMetrics.later(cursor, event.getEndedAt());
        }
        return Math.max(
                longest, PlannerRecordMetrics.seconds(cursor, piece.view().endedAt()));
    }
}
