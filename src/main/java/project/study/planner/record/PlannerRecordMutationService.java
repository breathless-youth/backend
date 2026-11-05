package project.study.planner.record;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.ConflictException;
import project.study.studysession.entity.StudySessionSource;
import project.study.subject.service.StudySubjectService;
import project.study.user.service.UserTimeZones;

/** 호출자의 독립 트랜잭션과 사용자 락 안에서만 실행한다. */
@Service
@RequiredArgsConstructor
class PlannerRecordMutationService {
    private final PlannerRecordService records;
    private final PlannerRecordRepository repository;
    private final StudySubjectService subjects;
    private final UserTimeZones userTimeZones;
    private final Clock clock;

    PlannerRecordResult mutate(Long userId, PlannerRecordOperation operation) {
        if (operation.action() == null) {
            throw new BadRequestException("action이 필요합니다");
        }
        return switch (operation.action()) {
            case "CREATE" -> create(userId, operation);
            case "UPDATE" -> update(userId, operation);
            case "DELETE" -> delete(userId, operation);
            default -> throw new BadRequestException("action은 CREATE, UPDATE, DELETE 중 하나여야 합니다");
        };
    }

    private PlannerRecordResult create(Long userId, PlannerRecordOperation operation) {
        if (operation.key() != null) {
            throw new BadRequestException("CREATE에는 key를 지정할 수 없습니다");
        }
        Span span = span(operation);
        assertSubject(userId, operation.subjectId(), null);
        assertFree(userId, span, null);
        userTimeZones.resolve(userId, operation.timeZone());
        String key = "m:" + span.start();
        repository.saveEdit(
                userId,
                new PlannerRecordEdit(
                        key, StudySessionSource.MANUAL, span.start(), span.end(), operation.subjectId(), false, false));
        return PlannerRecordResult.success(operation, key);
    }

    private PlannerRecordResult update(Long userId, PlannerRecordOperation operation) {
        PlannerRecordEdit current = records.current(userId, operation.key());
        Span span = span(operation);
        assertSubject(userId, operation.subjectId(), current.subjectId());
        assertFree(userId, span, current.key());
        if (current.source() == StudySessionSource.MANUAL) {
            userTimeZones.resolve(userId, operation.timeZone());
        }
        String key = current.source() == StudySessionSource.MANUAL ? "m:" + span.start() : current.key();
        if (!key.equals(current.key())) {
            repository.saveEdit(userId, current.tombstone());
        }
        boolean preserve = current.preserveOriginalTotals()
                && span.start().equals(current.startedAt())
                && span.end().equals(current.endedAt());
        repository.saveEdit(
                userId,
                new PlannerRecordEdit(
                        key, current.source(), span.start(), span.end(), operation.subjectId(), false, preserve));
        return PlannerRecordResult.success(operation, key);
    }

    private PlannerRecordResult delete(Long userId, PlannerRecordOperation operation) {
        PlannerRecordEdit current = records.current(userId, operation.key());
        repository.saveEdit(userId, current.tombstone());
        return PlannerRecordResult.success(operation, current.key());
    }

    private void assertSubject(Long userId, Long subjectId, Long currentSubjectId) {
        if (subjectId == null) {
            return;
        }
        subjects.assertOwned(userId, List.of(subjectId));
        if (!Objects.equals(subjectId, currentSubjectId)
                && subjects.lookup(List.of(subjectId), List.of())
                        .subjects()
                        .get(subjectId)
                        .deleted()) {
            throw new BadRequestException("지운 과목은 고를 수 없습니다");
        }
    }

    private void assertFree(Long userId, Span span, String except) {
        if (records.records(userId, span.start(), span.end()).stream()
                .anyMatch(record -> !record.key().equals(except))) {
            throw new ConflictException("다른 개인 기록과 시간이 겹칩니다");
        }
    }

    private Span span(PlannerRecordOperation operation) {
        if (operation.startedAt() == null || operation.endedAt() == null) {
            throw new BadRequestException("시작과 종료 시각이 필요합니다");
        }
        Instant start = operation.startedAt().truncatedTo(ChronoUnit.MINUTES);
        Instant end = operation.endedAt().truncatedTo(ChronoUnit.MINUTES);
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(Duration.ofHours(24)) > 0) {
            throw new BadRequestException("기록은 1분 이상 24시간 이하여야 합니다");
        }
        if (end.isAfter(clock.instant())) {
            throw new BadRequestException("미래 기록은 입력할 수 없습니다");
        }
        return new Span(start, end);
    }

    private record Span(Instant start, Instant end) {}
}
