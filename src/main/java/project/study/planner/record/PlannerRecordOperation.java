package project.study.planner.record;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record PlannerRecordOperation(
        @Schema(description = "사용자별 재시도 키. 같은 ID에는 같은 본문만 허용 (최대 100자)", example = "record-edit-001")
        String operationId,

        @Schema(description = "CREATE(수동 추가), UPDATE, DELETE", example = "UPDATE")
        String action,

        @Schema(description = "조회에서 받은 묶음 키. CREATE에는 생략", example = "c:2026-10-01T10:00:00Z")
        String key,

        Instant startedAt,
        Instant endedAt,

        @Schema(description = "수정 구간 전체에 적용할 과목. null이면 과목 없음", example = "3")
        Long subjectId,

        @Schema(description = "수동 입력 시 저장할 사용자 IANA 시간대. 조회 창에서 날짜별 절삭", example = "Asia/Seoul")
        String timeZone) {}
