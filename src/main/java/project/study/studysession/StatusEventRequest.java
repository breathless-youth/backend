package project.study.studysession;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record StatusEventRequest(
        @NotNull EventStatus status,
        @NotNull Instant startedAt,
        @NotNull Instant endedAt) {

    public StatusEvent toEntity() {
        return new StatusEvent(status, startedAt, endedAt);
    }
}
