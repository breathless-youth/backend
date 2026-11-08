package project.study.planner.record;

import java.time.Instant;
import project.study.studysession.entity.StudySessionSource;

record PlannerRecordEdit(
        String key,
        StudySessionSource source,
        Instant startedAt,
        Instant endedAt,
        Long subjectId,
        boolean deleted,
        boolean preserveOriginalTotals) {
    boolean overlaps(Instant from, Instant to) {
        return startedAt.isBefore(to) && endedAt.isAfter(from);
    }

    PlannerRecordEdit tombstone() {
        return new PlannerRecordEdit(key, source, startedAt, endedAt, subjectId, true, preserveOriginalTotals);
    }
}
