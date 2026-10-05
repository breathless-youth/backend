package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 일간 조회의 과목 1건 — {@link SubjectRef}의 이름·색에 그날의 할 일을 붙인다 (ADR-0026).
 * 세션 단건·제출 응답은 SubjectRef 그대로다.
 */
public record DaySubjectResponse(
        @Schema(description = "과목 ID", example = "3") Long id,
        @Schema(description = "과목 이름", example = "영어") String name,

        @Schema(description = "색 팔레트 인덱스(0..19) — GET /api/subjects의 colorIndex와 같다", example = "2")
        int colorIndex,

        @Schema(description = "지운 과목이면 true — 그날 세션이 참조했거나 그날의 할 일이 있을 때만 실린다", example = "false")
        boolean deleted,

        @Schema(
                description = "이 과목의 그날의 할 일 — 그날이 끝나기 전에 만들었고 그날 시작 전에 완료하거나 지우지 않은 것. "
                        + "미완료 포함, id 오름차순(GET /api/subjects와 같다). 없으면 []")
        List<DayTaskResponse> tasks) {}
