package project.study.planner.record;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "현재 개인 기록에 실제로 남은 조각. 조각 사이 빈칸은 삭제했거나 기록이 없는 시간이다")
public record PlannerRecordPiece(
        @Schema(description = "조각 시작. 초·소수초 보존") Instant startedAt,

        @Schema(description = "조각 종료(exclusive). 벽시계 기준이라 PAUSE 시간도 포함한다")
        Instant endedAt,

        @Schema(description = "과목. null이면 과목 없이 공부한 시간", example = "3")
        Long subjectId) {}
