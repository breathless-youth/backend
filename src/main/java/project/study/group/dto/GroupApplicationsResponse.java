package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** 그룹장용 신청 목록 — 이 그룹에서 답을 기다리는 신청만. 기한이 지난 신청은 작업이 돌기 전이라도 나오지 않는다. */
public record GroupApplicationsResponse(
        @Schema(description = "남은 자리 수 — 6명에서 지금 멤버 수를 뺀 값. 0이면 수락할 수 없다(`GROUP_FULL`)", example = "2")
        int remainingSeats,

        @Schema(description = "답을 기다리는 신청 — 신청한 순서(오래된 것부터)")
        List<GroupApplicationItem> applications) {

    public record GroupApplicationItem(
            @Schema(description = "신청 ID — 수락·거절·신고에 쓴다", example = "55")
            long applicationId,

            @Schema(description = "신청한 사람 userId", example = "42")
            long userId,

            @Schema(description = "신청한 사람 닉네임", example = "새벽형인간")
            String nickname,

            @Schema(description = "신청한 사람 프로필 이미지 URL — 없으면 null")
            String profileImageUrl,

            @Schema(description = "신청 소개 글 — 1~50자", example = "새벽 5시에 일어나는 공시생이에요")
            String introduction,

            @Schema(description = "신청한 때(UTC ISO-8601)", example = "2026-11-03T05:30:00Z")
            Instant appliedAt,

            @Schema(
                    description = "신청 만료 시각(UTC ISO-8601) — 신청한 지 3일 뒤. 이때까지 답이 없으면 사라진다",
                    example = "2026-11-06T05:30:00Z")
            Instant expiresAt) {}
}
