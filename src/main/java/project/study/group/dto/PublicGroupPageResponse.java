package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import project.study.common.category.StudyCategory;
import project.study.group.GroupJoinPolicy;

/** 공개 그룹 목록의 한 쪽 — 쪽 번호 방식이라 다음 쪽이 있는지만 준다. */
public record PublicGroupPageResponse(
        @Schema(description = "이 쪽의 공개 그룹 — 다 차지 않은 그룹 → 평균 많은 순 → 그룹 ID 작은 순, 쪽마다 순서가 바뀔 수 있어 FE가 groupId로 중복을 거른다")
        List<PublicGroupCard> groups,

        @Schema(description = "다음 쪽이 있으면 true — 없으면 목록의 끝이다", example = "true")
        boolean hasNext) {

    /** 이번 주 평균 순공 설명 — 공개 그룹 카드와 공개 그룹 보기가 같은 값을 쓴다. */
    static final String WEEKLY_AVERAGE_FOCUS_DESCRIPTION = "평균 순공(초) — 지금 멤버들의 이번 주(월요일 0시 KST부터) 앱 전체 순공 합 ÷ 지금 멤버 수. "
            + "주 중간에 들어온 멤버도 이번 주 순공이 모두 들어가고 나간 멤버는 빠진다. "
            + "측정 세션만, 1분 미만 조각 제외, 진행 중 포함, 약 1분마다 갱신";

    public record PublicGroupCard(
            @Schema(description = "그룹 ID", example = "10") long groupId,

            @Schema(description = "그룹 이름", example = "새벽 공시반")
            String name,

            @Schema(description = "시험·목표", example = "CIVIL_SERVICE")
            StudyCategory category,

            @Schema(description = "입장 방식 — APPROVAL 신청 후 입장, OPEN 바로 입장")
            GroupJoinPolicy joinPolicy,

            @Schema(description = "멤버 수 (최대 6) — 6이면 다 찬 그룹이다", example = "4")
            int memberCount,

            @Schema(description = WEEKLY_AVERAGE_FOCUS_DESCRIPTION, example = "18000")
            long weeklyAverageFocusSec,

            @Schema(description = "지금 공부 중인 멤버 수 — 이 그룹 공부방에 있거나(`IN_ROOM`) 다른 곳에서 측정 중(`STUDYING`)인 멤버", example = "2")
            int studyingCount,

            @Schema(description = "내가 이 그룹 멤버면 true", example = "false")
            boolean member,

            GroupTranslation translation) {}
}
