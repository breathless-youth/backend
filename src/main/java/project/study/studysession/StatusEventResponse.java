package project.study.studysession;

import java.time.Instant;

public record StatusEventResponse(EventStatus status, Instant startedAt, Instant endedAt) {

    public static StatusEventResponse from(StatusEvent event) {
        return new StatusEventResponse(event.getStatus(), event.getStartedAt(), event.getEndedAt());
    }
}
