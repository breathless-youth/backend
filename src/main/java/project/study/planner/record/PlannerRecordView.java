package project.study.planner.record;

import java.time.Instant;
import java.util.List;
import project.study.studysession.entity.StudySessionSource;

public record PlannerRecordView(
        String key,
        StudySessionSource source,
        Instant startedAt,
        Instant endedAt,
        long studySec,
        long focusSec,
        List<PlannerRecordSegment> subjectSegments) {}
