package project.study.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record SubjectResponse(
        @Schema(description = "과목 ID", example = "3") Long id,
        @Schema(description = "과목 이름", example = "수학") String name,

        @Schema(
                description = "색 팔레트 인덱스(0..19) — 만들 때 서버가 그 사용자의 살아있는 과목이 가장 적게 쓴 색을 배정하고 이후 바뀌지 않는다. "
                        + "앱은 이 값을 팔레트에 매핑만 한다 (ADR-0022)",
                example = "2")
        int colorIndex,

        @Schema(description = "이 과목에서 잰 누적 총 공부 시간(초) — 할 일을 골랐든 과목만 골랐든 전부의 합", example = "4360")
        long studySec,

        @Schema(description = "이 과목에서 잰 누적 순공 시간(초)", example = "4200")
        long focusSec,

        @Schema(description = "보이는 할 일 목록 — 미완료 전부 + 오늘(KST) 완료한 것, id 오름차순")
        List<TaskResponse> tasks) {}
