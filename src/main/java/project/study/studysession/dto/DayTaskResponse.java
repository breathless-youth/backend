package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 일간 조회의 과목 아래에 싣는 그날의 할 일 1건 — 미완료도 실리고, 완료했으면 완료 시각을 함께 준다 (ADR-0026).
 * 세션 제출 당시 기록인 {@link CompletedTaskResponse}와 달리 할 일의 지금 상태가 기준이다.
 */
public record DayTaskResponse(
        @Schema(description = "할 일 ID", example = "12") Long id,

        @Schema(description = "할 일 이름", example = "3단원 문제풀기")
        String name,

        @Schema(description = "지금 완료 상태인지 — 조회한 날짜 뒤에 완료한 것도 true다", example = "true")
        boolean done,

        @Schema(
                description =
                        "완료 시각 (UTC, ISO-8601) — 미완료면 null. 저장된 값 그대로라 조회한 날짜보다 뒤일 수 있다. " + "그날 완료했는지는 앱이 이 값으로 판단한다",
                example = "2026-10-04T12:30:00Z",
                nullable = true)
        Instant doneAt,

        @Schema(description = "지금 지워진 할 일이면 true — 과목을 지워 함께 지워진 것도 포함", example = "false")
        boolean deleted) {}
