package project.study.studysession.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import project.study.studysession.entity.StudySession;

public record StudySessionResponse(
        Long id,
        Long userId,
        LocalDate statDate,
        Instant startedAt,
        Instant endedAt,
        Integer sessionSec,
        Integer focusSec,
        List<StatusEventResponse> events) {

    public static StudySessionResponse from(StudySession session) {
        return new StudySessionResponse(
                session.getId(),
                session.getUserId(),
                session.getStatDate(),
                session.getStartedAt(),
                session.getEndedAt(),
                session.getSessionSec(),
                session.getFocusSec(),
                session.getEvents().stream().map(StatusEventResponse::from).toList());
    }
}
