package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import project.study.common.category.StudyCategory;
import project.study.group.GroupJoinPolicy;
import project.study.group.GroupRole;
import project.study.group.GroupVisibility;

/** 그룹 홈 — 멤버만 볼 수 있다. 이번 주 순위는 {@code members[].weekFocusSec}로 FE가 그린다. */
public record GroupHomeResponse(
        @Schema(description = "그룹 ID", example = "10") long groupId,
        @Schema(description = "그룹 이름", example = "새벽 공시반") String name,
        @Schema(description = "공개 여부", example = "PUBLIC") GroupVisibility visibility,

        @Schema(description = "입장 방식 — APPROVAL 신청 후 입장, OPEN 바로 입장")
        GroupJoinPolicy joinPolicy,

        @Schema(description = "시험·목표", example = "CIVIL_SERVICE")
        StudyCategory category,

        @Schema(description = "그룹 소개 — 없으면 null") String description,
        @Schema(description = "멤버 수 (최대 6)", example = "4") int memberCount,
        @Schema(description = "내 역할", example = "OWNER") GroupRole myRole,

        @Schema(description = "멤버 공부 알림을 받는지 — 처음엔 false", example = "false")
        boolean notifyEnabled,

        @Schema(
                description =
                        "새 신청 수 — 답을 기다리는 신청 전부. 그룹장이 목록을 봤는지는 따로 기록하지 않아 답할 때까지 남는다. APPROVAL 공개 그룹 그룹장이 아니면 null",
                example = "2")
        Integer newApplicationCount,

        @Schema(
                description = "지금 공부 중인 멤버 수 — members 중 status가 `IN_ROOM`(이 그룹 공부방)이거나 `STUDYING`(다른 곳에서 측정 중)인 수",
                example = "2")
        int studyingCount,

        @Schema(description = "멤버 책상 — 들어간 순서. 책상 순서·순위 정렬은 FE가 한다")
        List<GroupMemberDesk> members) {}
