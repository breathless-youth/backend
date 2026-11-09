package project.study.planner.record;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.ConflictException;
import project.study.common.exception.ErrorCode;
import project.study.planner.record.PlannerRecordService.PlannerRecordTarget;
import project.study.planner.record.PlannerRecordSpans.Span;
import project.study.studysession.entity.StudySessionSource;
import project.study.subject.service.StudySubjectService;
import project.study.user.service.UserTimeZones;

/** 호출자의 독립 트랜잭션과 사용자 락 안에서만 실행한다. */
@Service
@RequiredArgsConstructor
class PlannerRecordMutationService {
    static final int MAX_RANGES = 100;

    private final PlannerRecordService records;
    private final PlannerRecordRepository repository;
    private final StudySubjectService subjects;
    private final UserTimeZones userTimeZones;
    private final Clock clock;

    PlannerRecordResult mutate(Long userId, PlannerRecordOperation operation) {
        if (operation.action() == null) {
            throw new BadRequestException("action이 필요합니다");
        }
        // 범위를 무시하고 기록 전체를 지우거나 바꾸지 않도록 다른 action의 ranges는 받지 않는다.
        if (operation.ranges() != null && !"EDIT_RANGES".equals(operation.action())) {
            throw new BadRequestException("ranges는 EDIT_RANGES에만 보낼 수 있습니다");
        }
        return switch (operation.action()) {
            case "CREATE" -> create(userId, operation);
            case "UPDATE" -> update(userId, operation);
            case "DELETE" -> delete(userId, operation);
            case "EDIT_RANGES" -> editRanges(userId, operation);
            default -> throw new BadRequestException("action은 CREATE, UPDATE, DELETE, EDIT_RANGES 중 하나여야 합니다");
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
        assertKeyFree(userId, key);
        long revision = repository.saveEdit(
                userId,
                new PlannerRecordEdit(
                        key, StudySessionSource.MANUAL, span.start(), span.end(), operation.subjectId(), false, false));
        return PlannerRecordResult.success(operation, key, revision);
    }

    private PlannerRecordResult update(Long userId, PlannerRecordOperation operation) {
        PlannerRecordTarget target = records.target(userId, operation.key());
        assertRevision(target, operation.revision(), false);
        PlannerRecordEdit current = target.current();
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
        boolean preserve = current.preserveOriginalTotals() && unchanged;
        List<PlannerRecordPiece> pieces =
                current.pieces() == null ? null : resized(current, span, unchanged, operation.subjectId());
        if (!key.equals(current.key())) {
            assertKeyFree(userId, key);
            repository.saveEdit(userId, current.tombstone());
        }
        long revision = repository.saveEdit(
                userId,
                new PlannerRecordEdit(
                        key,
                        current.source(),
                        span.start(),
                        span.end(),
                        operation.subjectId(),
                        false,
                        preserve,
                        pieces,
                        0));
        return PlannerRecordResult.success(operation, key, revision);
    }

    /** 분 범위로 지운 빈칸은 전체 수정 뒤에도 되살리지 않는다. 새로 늘린 시간만 기록으로 더한다. */
    private static List<PlannerRecordPiece> resized(
            PlannerRecordEdit current, Span span, boolean unchanged, Long subjectId) {
        List<PlannerRecordPiece> pieces = unchanged
                ? PlannerRecordPieces.paintAll(current.pieces(), subjectId)
                : extended(current, span, subjectId);
        // 지운 빈칸만 남기면 보이지 않는데 삭제되지도 않은 기록이 된다.
        if (pieces.isEmpty()) {
            throw new BadRequestException("남은 기록이 없는 시간으로만 고칠 수 없습니다");
        }
        return pieces;
    }

    private static List<PlannerRecordPiece> extended(PlannerRecordEdit current, Span span, Long subjectId) {
        List<PlannerRecordPiece> pieces =
                new ArrayList<>(PlannerRecordPieces.clip(current.pieces(), span.start(), span.end()));
        if (span.start().isBefore(current.startedAt())) {
            pieces.add(new PlannerRecordPiece(
                    span.start(), PlannerRecordMetrics.earlier(span.end(), current.startedAt()), subjectId));
        }
        if (span.end().isAfter(current.endedAt())) {
            pieces.add(new PlannerRecordPiece(
                    PlannerRecordMetrics.later(span.start(), current.endedAt()), span.end(), subjectId));
        }
        return PlannerRecordPieces.paintAll(pieces, subjectId);
    }

    private PlannerRecordResult delete(Long userId, PlannerRecordOperation operation) {
        PlannerRecordTarget target = records.target(userId, operation.key());
        assertRevision(target, operation.revision(), false);
        long revision = repository.saveEdit(userId, target.current().tombstone());
        return PlannerRecordResult.success(operation, target.current().key(), revision);
    }

    /**
     * 한 기록의 여러 분 범위를 한 번에 적용한다. SUBJECT·DELETE는 현재 조각과의 교집합에만 적용해 시간을 늘리지 않는다.
     * ADD는 범위 안의 빈 부분만 채워 시간을 늘리므로 채운 부분만 겹침·미래·24시간 검사를 한다.
     * 원본 세션·이벤트·랭킹은 바꾸지 않는다.
     */
    private PlannerRecordResult editRanges(Long userId, PlannerRecordOperation operation) {
        if (operation.key() == null) {
            throw new BadRequestException("EDIT_RANGES에는 key가 필요합니다");
        }
        if (operation.startedAt() != null || operation.endedAt() != null || operation.subjectId() != null) {
            throw new BadRequestException("EDIT_RANGES에는 startedAt, endedAt, subjectId 대신 ranges를 보내야 합니다");
        }
        List<PlannerRecordRange> ranges = validRanges(operation.ranges());
        PlannerRecordTarget target = records.target(userId, operation.key());
        assertRevision(target, operation.revision(), true);
        PlannerRecordEdit current = target.current();
        List<PlannerRecordPiece> filled = filled(userId, current, target.pieces(), ranges);
        List<PlannerRecordPiece> pieces = applied(userId, target.pieces(), ranges, filled);
        if (pieces.isEmpty()) {
            long revision = repository.saveEdit(userId, current.tombstone());
            return PlannerRecordResult.success(operation, current.key(), revision);
        }
        long revision = repository.saveEdit(
                userId,
                new PlannerRecordEdit(
                        current.key(),
                        current.source(),
                        pieces.getFirst().startedAt(),
                        pieces.getLast().endedAt(),
                        PlannerRecordPieces.common(pieces),
                        false,
                        // UPDATE로 시간을 늘릴 때처럼 늘린 기록은 이벤트로 다시 센다.
                        current.preserveOriginalTotals() && filled.isEmpty(),
                        pieces,
                        0));
        return PlannerRecordResult.success(operation, current.key(), revision);
    }

    /**
     * ADD 범위가 채울 빈 부분. 범위는 서로 겹치지 않으므로 현재 조각 기준으로 미리 구해도 다른 범위의 적용 순서와 무관하다.
     * 채운 부분만 새 점유 시간이라 UPDATE로 늘릴 때와 같은 미래·겹침 검사를 여기서 한다.
     */
    private List<PlannerRecordPiece> filled(
            Long userId, PlannerRecordEdit current, List<PlannerRecordPiece> pieces, List<PlannerRecordRange> ranges) {
        List<PlannerRecordPiece> filled = new ArrayList<>();
        for (PlannerRecordRange range : ranges) {
            if (range.change().equals("ADD")) {
                filled.addAll(PlannerRecordPieces.gaps(pieces, range.startedAt(), range.endedAt(), range.subjectId()));
            }
        }
        Instant now = clock.instant();
        if (filled.stream().anyMatch(p -> p.endedAt().isAfter(now))) {
            throw new BadRequestException("미래 기록은 입력할 수 없습니다");
        }
        for (PlannerRecordPiece piece : filled) {
            assertFree(userId, new Span(piece.startedAt(), piece.endedAt()), current.key());
        }
        return filled;
    }

    /** 모든 범위를 먼저 검사한 뒤 적용해 일부 범위만 반영되는 일이 없게 한다. */
    private List<PlannerRecordPiece> applied(
            Long userId,
            List<PlannerRecordPiece> pieces,
            List<PlannerRecordRange> ranges,
            List<PlannerRecordPiece> filled) {
        assertApplicable(userId, pieces, ranges);
        List<PlannerRecordPiece> result = pieces;
        for (PlannerRecordRange range : ranges) {
            result = switch (range.change()) {
                case "SUBJECT" ->
                    PlannerRecordPieces.paint(result, range.startedAt(), range.endedAt(), range.subjectId());
                case "DELETE" -> PlannerRecordPieces.remove(result, range.startedAt(), range.endedAt());
                // 저장 전에 지운 다른 과목 자리로 늘리면 그 조각이 서버에는 남아 있다. 범위 전체를 이 과목으로 맞춘다.
                default -> PlannerRecordPieces.paint(result, range.startedAt(), range.endedAt(), range.subjectId());
            };
        }
        return filled.isEmpty() ? result : grown(result, filled);
    }

    private void assertApplicable(Long userId, List<PlannerRecordPiece> pieces, List<PlannerRecordRange> ranges) {
        for (PlannerRecordRange range : ranges) {
            // ADD는 빈 시간을 채우는 것이라 남은 조각과 겹치지 않아도 된다.
            if (!range.change().equals("ADD")
                    && !PlannerRecordPieces.intersects(pieces, range.startedAt(), range.endedAt())) {
                throw new ConflictException(ErrorCode.STALE_RECORD, "고칠 범위에 남은 기록이 없습니다. 다시 조회해 주세요");
            }
            if (!range.change().equals("DELETE")) {
                // 범위 안에 이미 지정된 과목은 지운 과목이어도 다시 보낼 수 있다.
                boolean kept = PlannerRecordPieces.clip(pieces, range.startedAt(), range.endedAt()).stream()
                        .anyMatch(p -> Objects.equals(p.subjectId(), range.subjectId()));
                assertSubject(userId, range.subjectId(), kept ? range.subjectId() : null);
            }
        }
    }

    /** 채운 조각을 더한 기록. 늘어난 전체 길이에 UPDATE와 같은 24시간 상한을 둔다. */
    private static List<PlannerRecordPiece> grown(List<PlannerRecordPiece> result, List<PlannerRecordPiece> filled) {
        List<PlannerRecordPiece> grown = new ArrayList<>(result);
        grown.addAll(filled);
        grown = PlannerRecordPieces.normalize(grown);
        if (Duration.between(grown.getFirst().startedAt(), grown.getLast().endedAt())
                        .compareTo(Duration.ofHours(24))
                > 0) {
            throw new BadRequestException("기록은 1분 이상 24시간 이하여야 합니다");
        }
        return grown;
    }

    private static List<PlannerRecordRange> validRanges(List<PlannerRecordRange> ranges) {
        if (ranges == null || ranges.isEmpty() || ranges.size() > MAX_RANGES) {
            throw new BadRequestException("ranges는 1~" + MAX_RANGES + "개여야 합니다");
        }
        List<PlannerRecordRange> sorted = new ArrayList<>();
        for (PlannerRecordRange range : ranges) {
            assertRange(range);
            sorted.add(range);
        }
        sorted.sort(Comparator.comparing(PlannerRecordRange::startedAt));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).startedAt().isBefore(sorted.get(i - 1).endedAt())) {
                throw new BadRequestException("한 작업의 범위끼리 겹칠 수 없습니다");
            }
        }
        return sorted;
    }

    private static void assertRange(PlannerRecordRange range) {
        if (range == null || range.startedAt() == null || range.endedAt() == null) {
            throw new BadRequestException("범위에는 시작과 종료 시각이 필요합니다");
        }
        if (!minute(range.startedAt()) || !minute(range.endedAt())) {
            throw new BadRequestException("범위 시각은 UTC 분 단위(초·소수초 0)여야 합니다");
        }
        if (!range.startedAt().isBefore(range.endedAt())) {
            throw new BadRequestException("범위 종료는 시작 이후여야 합니다");
        }
        assertChange(range);
    }

    private static void assertChange(PlannerRecordRange range) {
        if (!"SUBJECT".equals(range.change()) && !"DELETE".equals(range.change()) && !"ADD".equals(range.change())) {
            throw new BadRequestException("change는 SUBJECT, DELETE, ADD 중 하나여야 합니다");
        }
        if (range.change().equals("DELETE") && range.subjectId() != null) {
            throw new BadRequestException("DELETE 범위에는 subjectId를 보낼 수 없습니다");
        }
    }

    private static boolean minute(Instant time) {
        return time.getNano() == 0 && Math.floorMod(time.getEpochSecond(), 60) == 0;
    }

    private static void assertRevision(PlannerRecordTarget target, Long expected, boolean required) {
        if (expected == null) {
            if (required) {
                throw new BadRequestException("EDIT_RANGES에는 revision이 필요합니다");
            }
            return;
        }
        if (expected != target.revision()) {
            throw new ConflictException(ErrorCode.STALE_RECORD, "기록이 조회 뒤에 바뀌었습니다. 다시 조회해 주세요");
        }
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

    /** 앞부분을 지운 수동 기록은 시작 시각이 키와 달라 겹침 검사를 통과하므로 키로 한 번 더 막는다. */
    private void assertKeyFree(Long userId, String key) {
        if (repository.edits(userId).stream().anyMatch(e -> e.key().equals(key) && !e.deleted())) {
            throw new ConflictException("같은 시작 시각의 개인 기록이 이미 있습니다");
        }
    }

    private void assertFree(Long userId, Span span, String except) {
        if (records.records(userId, span.start(), span.end()).stream()
                .anyMatch(record -> !record.key().equals(except))) {
            throw new ConflictException("다른 개인 기록과 시간이 겹칩니다");
        }
    }
}
