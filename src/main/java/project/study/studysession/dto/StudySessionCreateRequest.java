package project.study.studysession.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

public record StudySessionCreateRequest(
        @NotNull Long userId,
        @NotNull Instant startedAt,
        @NotNull Instant endedAt,
        @NotNull @Valid List<StatusEventRequest> events) {}
