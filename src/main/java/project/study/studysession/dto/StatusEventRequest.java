package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;

public record StatusEventRequest(
        @Schema(description = "비공부 상태 종류 — 이 구간들은 순공 시간(focusSec)에서 제외된다", example = "PHONE") @NotNull
        EventStatus status,

        @Schema(description = "이벤트 시작 시각 (UTC, ISO-8601) — 세션 구간 안이어야 한다", example = "2026-07-24T01:10:00Z") @NotNull
        Instant startedAt,

        @Schema(
                description = "이벤트 종료 시각 (UTC, ISO-8601) — 시작 이후여야 하고, 다른 이벤트와 겹칠 수 없다",
                example = "2026-07-24T01:20:00Z")
        @NotNull
        Instant endedAt) {

    public StatusEvent toEntity() {
        return new StatusEvent(status, startedAt, endedAt);
    }
}
