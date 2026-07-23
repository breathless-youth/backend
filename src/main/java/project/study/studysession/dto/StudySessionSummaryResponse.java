package project.study.studysession.dto;

import java.time.Instant;
import java.time.LocalDate;
import project.study.studysession.entity.StudySession;

public record StudySessionSummaryResponse(
        Long id, LocalDate statDate, Instant startedAt, Instant endedAt, Integer sessionSec, Integer focusSec) {

    public static StudySessionSummaryResponse from(StudySession session) {
        return new StudySessionSummaryResponse(
                session.getId(),
                session.getStatDate(),
                session.getStartedAt(),
                session.getEndedAt(),
                session.getSessionSec(),
                session.getFocusSec());
    }
}
