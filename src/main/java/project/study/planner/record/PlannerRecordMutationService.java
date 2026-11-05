package project.study.planner.record;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.ConflictException;
import project.study.planner.record.PlannerRecordSpans.Span;
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
        Span span = PlannerRecordSpans.create(operation, clock.instant());
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
        Span span = PlannerRecordSpans.update(operation, current, clock.instant());
        assertSubject(userId, operation.subjectId(), current.subjectId());
        boolean unchanged =
                span.start().equals(current.startedAt()) && span.end().equals(current.endedAt());
        // 기존 수동 묶음은 다른 기록을 피해 여러 조각일 수 있다. 과목만 바꾸면 점유 시간은 늘지 않는다.
        if (!unchanged) {
            assertFree(userId, span, current.key());
        }
        if (current.source() == StudySessionSource.MANUAL) {
            userTimeZones.resolve(userId, operation.timeZone());
        }
        String key =
                current.source() == StudySessionSource.MANUAL && !span.start().equals(current.startedAt())
                        ? "m:" + span.start()
                        : current.key();
        if (!key.equals(current.key())) {
            repository.saveEdit(userId, current.tombstone());
        }
        boolean preserve = current.preserveOriginalTotals() && unchanged;
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
}
