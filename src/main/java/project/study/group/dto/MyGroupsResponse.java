package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import project.study.common.category.StudyCategory;
import project.study.group.GroupVisibility;

/** 내 그룹 탭 — 들어간 그룹 카드와 신청·그룹장 수, 한도. */
public record MyGroupsResponse(
        @Schema(description = "내가 들어간 그룹 — 들어간 순서") List<MyGroupCard> groups,

        @Schema(description = "답을 기다리는 내 신청 수 (최대 3)", example = "1")
        int pendingApplicationCount,

        @Schema(description = "내가 그룹장인 그룹 수", example = "1") int ownedGroupCount,

        @Schema(description = "한도 — 화면의 \"n/5\" 같은 표시와 버튼 막기에 쓴다")
        GroupLimits limits) {

    public record MyGroupCard(
            @Schema(description = "그룹 ID", example = "10") long groupId,

            @Schema(description = "그룹 이름", example = "새벽 공시반")
            String name,

            @Schema(description = "시험·목표", example = "CIVIL_SERVICE")
            StudyCategory category,

            @Schema(description = "공개 여부", example = "PUBLIC")
            GroupVisibility visibility,

            @Schema(description = "멤버 수 (최대 6)", example = "4")
            int memberCount,

            @Schema(
                    description = "지금 공부 중인 멤버 수 — members 중 status가 `IN_ROOM`(이 그룹 공부방)이거나 `STUDYING`(다른 곳에서 측정 중)인 수",
                    example = "2")
            int studyingCount,

            @Schema(description = "내가 이 그룹의 그룹장이면 true", example = "true")
            boolean owner,

            @Schema(description = "멤버 책상 — 들어간 순서. 정렬은 FE가 한다")
            List<GroupMemberDesk> members) {}

    public record GroupLimits(
            @Schema(description = "들어간 그룹과 답을 기다리는 신청을 합쳐 가질 수 있는 수", example = "5")
            int maxGroupsAndApplications,

            @Schema(description = "동시에 답을 기다릴 수 있는 신청 수", example = "3")
            int maxPendingApplications,

            @Schema(description = "쓰지 않는다 — 그룹장 한도는 없어졌고 이미 배포된 웹을 위해 남겨 두었다", example = "2", deprecated = true)
            int maxOwnedGroups) {}
}
