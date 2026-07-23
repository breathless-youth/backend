package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;

public record StatusEventResponse(
        @Schema(description = "비공부 상태 종류", example = "PHONE")
        EventStatus status,

        @Schema(description = "이벤트 시작 시각 (UTC, ISO-8601)", example = "2026-07-24T01:10:00Z")
        Instant startedAt,

        @Schema(description = "이벤트 종료 시각 (UTC, ISO-8601)", example = "2026-07-24T01:20:00Z")
        Instant endedAt) {

    public static StatusEventResponse from(StatusEvent event) {
        return new StatusEventResponse(event.getStatus(), event.getStartedAt(), event.getEndedAt());
    }
}
