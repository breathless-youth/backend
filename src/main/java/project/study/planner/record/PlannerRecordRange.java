package project.study.planner.record;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record PlannerRecordRange(
        @Schema(description = "UTC 분 시작. 초·소수초는 0이어야 한다", example = "2026-11-09T03:00:00Z")
        Instant startedAt,

        @Schema(description = "UTC 분 종료(exclusive). 시작보다 1분 이상 뒤", example = "2026-11-09T03:01:00Z")
        Instant endedAt,

        @Schema(
                description =
                        "SUBJECT(겹친 부분의 모든 과목을 subjectId로), DELETE(겹친 부분을 개인 기록에서 제외), ADD(범위 전체를 subjectId 과목으로 맞춤. 있는 조각은 과목을 바꾸고 빈 부분은 채워 기록을 늘림)",
                example = "SUBJECT")
        String change,

        @Schema(description = "SUBJECT·ADD의 과목. null이면 과목 없음. DELETE에는 생략", example = "7")
        Long subjectId) {}
