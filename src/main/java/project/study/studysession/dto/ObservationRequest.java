package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import project.study.studysession.entity.ObservationType;
import project.study.studysession.entity.SessionObservation;

/** 관찰 기록 1건 — 순공에 영향이 없다. 기록끼리 겹쳐도 된다. */
public record ObservationRequest(
        @Schema(description = "관찰 종류. YAWN=하품. 모르는 값이면 400", example = "YAWN") @NotNull
        ObservationType type,

        @Schema(description = "시작 시각 (UTC, ISO-8601) — 세션 구간 안이어야 한다", example = "2026-07-24T01:10:00Z") @NotNull
        Instant startedAt,

        @Schema(
                description = "끝 시각 (UTC, ISO-8601, 선택) — 있으면 시작과 같거나 이후이고 세션 구간 안이어야 한다. 순간 기록이면 생략",
                example = "2026-07-24T01:10:04Z")
        Instant endedAt) {

    public SessionObservation toEmbeddable() {
        return new SessionObservation(type, startedAt, endedAt);
    }
}
