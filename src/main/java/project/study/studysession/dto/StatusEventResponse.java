package project.study.studysession.dto;

import java.time.Instant;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;

public record StatusEventResponse(EventStatus status, Instant startedAt, Instant endedAt) {

    public static StatusEventResponse from(StatusEvent event) {
        return new StatusEventResponse(event.getStatus(), event.getStartedAt(), event.getEndedAt());
    }
}
