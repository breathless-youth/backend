package project.study.planner.record;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PlannerRecordBatchRequest(
        @NotEmpty(message = "작업을 하나 이상 보내야 합니다") @Size(max = 50, message = "작업은 최대 50개까지 가능합니다")
        List<PlannerRecordOperation> operations) {}
