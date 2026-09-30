package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 재접속 복구용 진행중 세션 스냅샷 (BY-448) — 하트비트 요청(ActiveSessionSnapshotRequest)과 같은 모양이라
 * 클라이언트가 그대로 상태를 복원해 같은 startedAt으로 보고를 재개하거나 즉시 최종 제출할 수 있다.
 */
public record ActiveSessionSnapshotResponse(
        @Schema(description = "세션 시작 시각 — 최종 제출의 startedAt과 같은 값(세션 식별자)", example = "2026-08-27T01:00:00Z")
        Instant startedAt,

        @Schema(description = "서버가 아는 마지막 공부 시점(마지막 스냅샷의 기준 시각)", example = "2026-08-27T01:10:30Z")
        Instant reportedAt,

        @Schema(description = "마지막 스냅샷까지의 누적 총 공부 시간(초)", example = "600")
        Integer studySec,

        @Schema(description = "마지막 스냅샷까지의 누적 순공 시간(초)", example = "540")
        Integer focusSec,

        @Schema(description = "마지막 스냅샷까지의 비공부 이벤트 전체") List<StatusEventRequest> events) {}
