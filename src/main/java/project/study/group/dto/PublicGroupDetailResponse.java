package project.study.group.dto;

import static project.study.group.dto.PublicGroupPageResponse.WEEKLY_AVERAGE_FOCUS_DESCRIPTION;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import project.study.common.category.StudyCategory;
import project.study.common.exception.ErrorCode;
import project.study.group.ApplyState;

/** 공개 그룹 보기 — 신청하기 전에 그룹을 살펴보는 화면. 비공개 그룹이면 404 `GROUP_NOT_FOUND`다. */
public record PublicGroupDetailResponse(
        @Schema(description = "그룹 ID", example = "10") long groupId,
        @Schema(description = "그룹 이름", example = "새벽 공시반") String name,

        @Schema(description = "시험·목표", example = "CIVIL_SERVICE")
        StudyCategory category,

        @Schema(description = "그룹 소개 — 없으면 null") String description,
        @Schema(description = "멤버 수 (최대 6)", example = "4") int memberCount,
        @Schema(description = "그룹장") GroupOwnerProfile owner,

        @Schema(description = WEEKLY_AVERAGE_FOCUS_DESCRIPTION, example = "18000")
        long weeklyAverageFocusSec,

        @Schema(description = "멤버 책상 — 들어간 순서. 정렬은 FE가 한다") List<GroupMemberDesk> members,

        @Schema(
                description = "내가 할 수 있는 것 — 먼저 맞는 것 하나: `MEMBER` 이미 멤버, `APPLIED` 답을 기다리는 신청이 있음, "
                        + "`FULL` 멤버가 다 참(6명), `UNAVAILABLE` 신청할 수 없음(이유는 unavailableReason), `CAN_APPLY` 신청할 수 있음",
                example = "CAN_APPLY")
        ApplyState applyState,

        @Schema(
                description = "신청할 수 없는 이유 — applyState가 `UNAVAILABLE`일 때만, 먼저 맞는 것 하나: "
                        + "`RECENTLY_KICKED` 이 그룹에서 내보내진 지 3일이 안 됨, `GROUP_SLOT_LIMIT` 들어간 그룹과 답을 기다리는 신청이 합쳐 5개, "
                        + "`APPLICATION_LIMIT` 답을 기다리는 신청이 3곳. 그 밖에는 null",
                allowableValues = {"RECENTLY_KICKED", "GROUP_SLOT_LIMIT", "APPLICATION_LIMIT"})
        ErrorCode unavailableReason,

        @Schema(description = "내 대기 중인 신청 — applyState가 `APPLIED`일 때만, 아니면 null")
        PendingApplicationRef application,

        GroupTranslation translation) {

    public record GroupOwnerProfile(
            @Schema(description = "그룹장 userId", example = "7")
            long userId,

            @Schema(description = "그룹장 닉네임", example = "새벽형인간")
            String nickname,

            @Schema(description = "프로필 이미지 URL — 없으면 null") String profileImageUrl) {}

    public record PendingApplicationRef(
            @Schema(description = "신청 ID — 신청 취소에 쓴다", example = "55")
            long applicationId,

            @Schema(
                    description = "신청 만료 시각(UTC ISO-8601) — 신청한 지 3일 뒤. 이때까지 답이 없으면 사라진다",
                    example = "2026-11-06T05:30:00Z")
            Instant expiresAt) {}
}
