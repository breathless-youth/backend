package project.study.planner.record;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record PlannerRecordOperation(
        @Schema(description = "사용자별 재시도 키. 같은 ID에는 같은 본문만 허용 (최대 100자)", example = "record-edit-001")
        String operationId,

        @Schema(description = "CREATE(수동 추가), UPDATE, DELETE, EDIT_RANGES(분 범위 과목 변경·삭제·빈 시간 채우기)", example = "UPDATE")
        String action,

        @Schema(description = "조회에서 받은 묶음 키. CREATE에는 생략", example = "c:2026-10-01T10:00:00Z")
        String key,

        @Schema(description = "전체 시작 시각. CREATE 필수. UPDATE는 두 시각 모두 생략하면 보존, 하나만 생략하면 400. 변경 없는 경계는 초·소수초까지 보존")
        Instant startedAt,

        @Schema(description = "전체 종료 시각. UPDATE 명시 시 fullStartedAt/fullEndedAt 사용. CREATE와 실제 변경한 경계만 분 단위 내림")
        Instant endedAt,

        @Schema(description = "수정 구간 전체에 적용할 과목. null이면 과목 없음", example = "3")
        Long subjectId,

        @Schema(description = "수동 입력 시 저장할 사용자 IANA 시간대. 조회 창에서 날짜별 절삭", example = "Asia/Seoul")
        String timeZone,

        @Schema(
                description = "조회에서 받은 기록 버전. EDIT_RANGES는 필수, UPDATE/DELETE는 보내면 검사. 다르면 항목 409 STALE_RECORD",
                example = "2")
        Long revision,

        @Schema(description = "EDIT_RANGES 전용. 한 기록에 적용할 분 범위(최대 100개, 서로 겹치지 않음). 전부 적용하거나 전부 실패한다")
        List<PlannerRecordRange> ranges) {

    /** 범위 수정 이전 작업과 같은 재시도 해시를 만들기 위한 이전 본문 모양. 필드 이름·순서를 바꾸지 않는다. */
    record Legacy(
            String operationId,
            String action,
            String key,
            Instant startedAt,
            Instant endedAt,
            Long subjectId,
            String timeZone) {}

    Object hashBody() {
        return revision == null && ranges == null
                ? new Legacy(operationId, action, key, startedAt, endedAt, subjectId, timeZone)
                : this;
    }
}
