package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 세션에서 완료한 할 일 1건 — 이름을 함께 준다 (BY-734). 할 일 목록 API는 "미완료 + 오늘 완료"만 보여주므로
 * 어제 완료한 할 일의 이름은 이 응답에서만 얻을 수 있다.
 */
public record CompletedTaskResponse(
        @Schema(description = "할 일 ID", example = "12") Long id,

        @Schema(description = "할 일 이름", example = "문제집 1장 풀기")
        String name,

        @Schema(description = "과목 ID — 응답의 subjects[]에서 이름·색을 찾는다", example = "3")
        Long subjectId,

        @Schema(description = "지운 할 일이면 true — 기록은 남는다", example = "false")
        boolean deleted) {}
