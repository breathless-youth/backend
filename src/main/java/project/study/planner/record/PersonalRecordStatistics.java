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

    /** 분 범위로 지운 빈칸에서도 집중이 끊긴 것으로 본다. */
    static long longest(PersonalRecordPiece piece) {
        List<PlannerRecordPiece> spans = PlannerRecordPieces.spans(piece.view().pieces());
        if (spans.isEmpty()) {
            return longest(
                    piece.events(), piece.view().startedAt(), piece.view().endedAt());
        }
        long longest = 0;
        for (PlannerRecordPiece span : spans) {
            longest = Math.max(
                    longest,
                    longest(
                            PersonalRecordPieces.clip(piece.events(), span.startedAt(), span.endedAt()),
                            span.startedAt(),
                            span.endedAt()));
        }
        return longest;
    }

    private static long longest(List<StatusEvent> events, Instant start, Instant end) {
        Instant cursor = start;
        long longest = 0;
        for (StatusEvent event : events.stream()
                .sorted(Comparator.comparing(StatusEvent::getStartedAt))
                .toList()) {
            longest = Math.max(longest, PlannerRecordMetrics.seconds(cursor, event.getStartedAt()));
            cursor = PlannerRecordMetrics.later(cursor, event.getEndedAt());
        }
        return Math.max(longest, PlannerRecordMetrics.seconds(cursor, end));
    }
}
