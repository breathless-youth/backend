package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 세션 응답이 참조한 과목의 이름·색 (BY-734). 지운 과목도 실린다 — 세션 기록은 남기므로(ADR-0021 §1) 화면이
 * 이름을 그릴 수 있어야 하는데, 과목 목록 API는 살아있는 과목만 주기 때문이다.
 */
public record SubjectRef(
        @Schema(description = "과목 ID", example = "3") Long id,
        @Schema(description = "과목 이름", example = "영어") String name,

        @Schema(description = "색 팔레트 인덱스(0..19) — GET /api/subjects의 colorIndex와 같다", example = "2")
        int colorIndex,

        @Schema(description = "지운 과목이면 true — 목록 API에는 안 나오지만 기록엔 남는다", example = "false")
        boolean deleted) {}
