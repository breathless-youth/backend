package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;

/** 이벤트 1건은 status/startedAt/endedAt 3개 필드만 보내면 된다 — 길이(duration)는 서버가 계산하므로 별도로 보내지 않는다. */
public record StatusEventRequest(
        @Schema(
                description = "비공부 상태 종류. PHONE=휴대폰 사용, DEVICE=다른 기기 사용, AWAY=자리 비움, PAUSE=일시정지(앱에서 직접 멈춤). "
                        + "PAUSE만 총 공부 타이머(studySec)도 함께 멈추고, 나머지 셋은 순공 타이머(focusSec)만 멈춘다. "
                        + "상태별 발생 건수는 응답의 eventCounts에 집계되어 내려온다",
                example = "PHONE")
        @NotNull
        EventStatus status,

        @Schema(
                description = "이벤트 시작 시각 (UTC, ISO-8601) — 세션 구간(startedAt~endedAt) 안이어야 한다",
                example = "2026-07-24T01:10:00Z")
        @NotNull
        Instant startedAt,

        @Schema(
                description = "이벤트 종료 시각 (UTC, ISO-8601) — 시작 이후여야 하고, 다른 이벤트와 겹칠 수 없다(끝과 시작이 맞닿는 것은 허용)",
                example = "2026-07-24T01:20:00Z")
        @NotNull
        Instant endedAt) {

    public StatusEvent toEntity() {
        return new StatusEvent(status, startedAt, endedAt);
    }
}
