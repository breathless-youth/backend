package project.study.studysession.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;

public record StatusEventRequest(
        @NotNull EventStatus status,
        @NotNull Instant startedAt,
        @NotNull Instant endedAt) {

    public StatusEvent toEntity() {
        return new StatusEvent(status, startedAt, endedAt);
    }
}
