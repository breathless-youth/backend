package project.study.planner.record;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;

/** 원본 이벤트는 시각을 옮기지 않는다. 겹친 이벤트가 있는 이전 기록도 합집합으로 한 번만 뺀다. */
final class PlannerRecordMetrics {
    private PlannerRecordMetrics() {}

    record Times(long study, long focus) {}

    static Times recompute(Instant start, Instant end, List<StatusEvent> events) {
        return new Times(active(start, end, events, true), active(start, end, events, false));
    }

    /** 원래 제출값은 보존하면서 이벤트가 발생한 위치에 맞춰 창의 몫을 배분한다. */
    static long share(
            long value,
            Instant start,
            Instant end,
            Instant from,
            Instant to,
            List<StatusEvent> events,
            boolean pauseOnly) {
        long total = active(start, end, events, pauseOnly);
        if (total == 0) {
            total = seconds(start, end);
            return portion(value, seconds(start, to), total) - portion(value, seconds(start, from), total);
        }
        return portion(value, active(start, to, events, pauseOnly), total)
                - portion(value, active(start, from, events, pauseOnly), total);
    }

    private static long portion(long value, long active, long total) {
        return total <= 0
                ? 0
                : BigInteger.valueOf(value)
                        .multiply(BigInteger.valueOf(active))
                        .divide(BigInteger.valueOf(total))
                        .longValue();
    }

    private static long active(Instant start, Instant end, List<StatusEvent> events, boolean pauseOnly) {
        List<Span> spans = new ArrayList<>();
        for (StatusEvent event : events) {
            if (event.getStartedAt() == null || event.getEndedAt() == null) {
                continue;
            }
            if (pauseOnly && event.getStatus() != EventStatus.PAUSE) {
                continue;
            }
            Instant left = later(start, event.getStartedAt());
            Instant right = earlier(end, event.getEndedAt());
            if (left.isBefore(right)) {
                spans.add(new Span(left, right));
            }
        }
        spans.sort(Comparator.comparing(Span::start));
        long blocked = 0;
        Instant cursor = start;
        for (Span span : spans) {
            Instant left = later(cursor, span.start());
            blocked += seconds(left, span.end());
            cursor = later(cursor, span.end());
        }
        return Math.max(0, seconds(start, end) - blocked);
    }

    static long seconds(Instant start, Instant end) {
        return start.isBefore(end) ? Duration.between(start, end).toSeconds() : 0;
    }

    static Instant earlier(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    static Instant later(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private record Span(Instant start, Instant end) {}
}
