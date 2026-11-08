package project.study.planner.record;

import io.swagger.v3.oas.annotations.media.Schema;

public record PlannerRecordResult(
        String operationId,
        boolean success,
        String key,

        @Schema(description = "성공 후 기록 버전. 실패와 이전에 저장된 결과는 null", example = "3")
        Long revision,

        int status,
        String code,
        String message) {

    static PlannerRecordResult success(PlannerRecordOperation operation, String key, long revision) {
        return new PlannerRecordResult(operation.operationId(), true, key, revision, 200, null, null);
    }

    static PlannerRecordResult failure(PlannerRecordOperation operation, int status, String code, String message) {
        return new PlannerRecordResult(
                operation == null ? null : operation.operationId(), false, null, null, status, code, message);
    }
}
