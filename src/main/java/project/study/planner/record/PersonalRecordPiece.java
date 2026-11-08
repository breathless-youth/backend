package project.study.planner.record;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import project.study.studysession.dto.StatusEventResponse;
import project.study.studysession.dto.SubjectLookup;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySessionSource;

record PersonalRecordPiece(
        String displayKey,
        Long originalSessionId,
        LocalDate date,
        ZoneId zone,
        PlannerRecordView view,
        List<StatusEvent> events,
        Set<Long> taskIds,
        boolean rhythmEligible,
        Instant submissionStartedAt) {
    boolean measured() {
        return view.source() == StudySessionSource.MEASURED;
    }

    PersonalRecordSummaryResponse summary(SubjectLookup lookup) {
        return new PersonalRecordSummaryResponse(
                displayKey,
                view.key(),
                originalSessionId,
                view.edited(),
                date,
                view.startedAt(),
                view.endedAt(),
                view.fullStartedAt(),
                view.fullEndedAt(),
                view.studySec(),
                view.focusSec(),
                PersonalRecordStatistics.rate(view.focusSec(), view.studySec()),
                PersonalRecordStatistics.counts(events),
                events.stream().map(StatusEventResponse::from).toList(),
                view.subjectSegments(),
                lookup.tasksFor(taskIds),
                view.source(),
                submissionStartedAt);
    }
}
