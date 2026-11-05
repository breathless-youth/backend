package project.study.planner.record;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import project.study.common.exception.BadRequestException;

final class PlannerRecordSpans {
    private PlannerRecordSpans() {}

    static Span create(PlannerRecordOperation operation, Instant now) {
        requireBoth(operation);
        return validate(minute(operation.startedAt()), minute(operation.endedAt()), now);
    }

    static Span update(PlannerRecordOperation operation, PlannerRecordEdit current, Instant now) {
        if (operation.startedAt() == null && operation.endedAt() == null) {
            return new Span(current.startedAt(), current.endedAt());
        }
        requireBoth(operation);
        boolean sameStart = operation.startedAt().equals(current.startedAt());
        boolean sameEnd = operation.endedAt().equals(current.endedAt());
        if (sameStart && sameEnd) {
            return new Span(current.startedAt(), current.endedAt());
        }
        return validate(
                sameStart ? current.startedAt() : minute(operation.startedAt()),
                sameEnd ? current.endedAt() : minute(operation.endedAt()),
                now);
    }

    private static void requireBoth(PlannerRecordOperation operation) {
        if (operation.startedAt() == null || operation.endedAt() == null) {
            throw new BadRequestException("시작과 종료 시각을 함께 보내야 합니다");
        }
    }

    private static Instant minute(Instant time) {
        return time.truncatedTo(ChronoUnit.MINUTES);
    }

    private static Span validate(Instant start, Instant end, Instant now) {
        Duration duration = Duration.between(start, end);
        if (duration.compareTo(Duration.ofMinutes(1)) < 0 || duration.compareTo(Duration.ofHours(24)) > 0) {
            throw new BadRequestException("기록은 1분 이상 24시간 이하여야 합니다");
        }
        if (end.isAfter(now)) {
            throw new BadRequestException("미래 기록은 입력할 수 없습니다");
        }
        return new Span(start, end);
    }

    record Span(Instant start, Instant end) {}
}
