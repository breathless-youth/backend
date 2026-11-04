package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import project.study.common.category.StudyCategory;

/** 내 신청 목록 — 내가 보낸 신청 중 답을 기다리는 것만. 기한이 지난 신청은 작업이 돌기 전이라도 나오지 않는다. */
public record MyApplicationsResponse(
        @Schema(description = "답을 기다리는 내 신청 — 신청한 순서(오래된 것부터). 최대 3개")
        List<MyApplicationItem> applications) {

    public record MyApplicationItem(
            @Schema(description = "신청 ID — 신청 취소에 쓴다", example = "55")
            long applicationId,

            @Schema(description = "신청한 그룹 ID", example = "10")
            long groupId,

            @Schema(description = "신청한 그룹 이름", example = "새벽 공시반")
            String groupName,

            @Schema(description = "신청한 그룹의 시험·목표", example = "CIVIL_SERVICE")
            StudyCategory category,

            @Schema(description = "신청한 때(UTC ISO-8601)", example = "2026-11-03T05:30:00Z")
            Instant appliedAt,

            @Schema(
                    description = "신청 만료 시각(UTC ISO-8601) — 신청한 지 3일 뒤. 이때까지 답이 없으면 사라진다",
                    example = "2026-11-06T05:30:00Z")
            Instant expiresAt) {}
}
