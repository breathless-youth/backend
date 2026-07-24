package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

public record StudySessionCreateRequest(
        @Schema(description = "세션 주인의 유저 ID (POST /api/users 로 발급받은 값)", example = "1") @NotNull
        Long userId,

        @Schema(description = "세션 시작 시각 (UTC, ISO-8601)", example = "2026-07-24T01:00:00Z") @NotNull
        Instant startedAt,

        @Schema(
                description = "세션 종료 시각 (UTC, ISO-8601) — 시작 이후·24시간 이내·미래 불가(시계 오차 5분 허용)",
                example = "2026-07-24T03:00:00Z")
        @NotNull
        Instant endedAt,

        @Schema(description = "앱이 계산한 순공 시간(초) — 필수지만 서버는 신뢰하지 않고 이벤트 기반으로 재계산한다", example = "130") @NotNull
        Integer focusSec,

        @Schema(description = "세션 중 발생한 비공부 상태 이벤트 목록 — 없으면 빈 배열. 순서는 뒤섞여도 된다(서버가 정렬)") @NotNull @Valid
        List<StatusEventRequest> events) {}
