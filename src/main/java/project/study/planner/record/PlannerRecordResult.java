package project.study.planner.record;

public record PlannerRecordResult(
        String operationId, boolean success, String key, int status, String code, String message) {

    static PlannerRecordResult success(PlannerRecordOperation operation, String key) {
        return new PlannerRecordResult(operation.operationId(), true, key, 200, null, null);
    }

    static PlannerRecordResult failure(PlannerRecordOperation operation, int status, String code, String message) {
        return new PlannerRecordResult(
                operation == null ? null : operation.operationId(), false, null, status, code, message);
    }
}
