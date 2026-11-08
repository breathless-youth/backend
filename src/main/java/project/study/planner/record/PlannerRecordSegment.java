package project.study.planner.record;

import java.time.Instant;

public record PlannerRecordSegment(Long subjectId, Instant startedAt, Instant endedAt, long studySec, long focusSec) {}
