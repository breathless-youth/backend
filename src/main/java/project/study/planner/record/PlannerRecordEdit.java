package project.study.planner.record;

import java.time.Instant;
import java.util.List;
import project.study.studysession.entity.StudySessionSource;

/**
 * 개인 수정본 한 행. {@code pieces}가 null이면 [startedAt,endedAt) 전체가 subjectId 하나인 기존 규칙이고,
 * 있으면 분 범위 수정으로 남은 실제 조각이다(startedAt/endedAt은 첫 조각 시작·마지막 조각 끝).
 */
record PlannerRecordEdit(
        String key,
        StudySessionSource source,
        Instant startedAt,
        Instant endedAt,
        Long subjectId,
        boolean deleted,
        boolean preserveOriginalTotals,
        List<PlannerRecordPiece> pieces,
        long revision) {
    PlannerRecordEdit(
            String key,
            StudySessionSource source,
            Instant startedAt,
            Instant endedAt,
            Long subjectId,
            boolean deleted,
            boolean preserveOriginalTotals) {
        this(key, source, startedAt, endedAt, subjectId, deleted, preserveOriginalTotals, null, 0);
    }

    boolean overlaps(Instant from, Instant to) {
        return startedAt.isBefore(to) && endedAt.isAfter(from);
    }

    PlannerRecordEdit tombstone() {
        return new PlannerRecordEdit(
                key, source, startedAt, endedAt, subjectId, true, preserveOriginalTotals, pieces, revision);
    }
}
