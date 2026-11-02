package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** 멤버 목록 — 그룹 멤버만 볼 수 있다. 지금 공부 중인지 같은 책상 정보는 그룹 홈의 멤버 책상이 준다. */
public record GroupMembersResponse(
        @Schema(description = "멤버 — 들어간 순서. 최대 6명") List<GroupMemberItem> members) {

    public record GroupMemberItem(
            @Schema(description = "멤버 userId", example = "42")
            long userId,

            @Schema(description = "닉네임", example = "새벽형인간") String nickname,
            @Schema(description = "프로필 이미지 URL — 없으면 null") String profileImageUrl,

            @Schema(description = "그룹장이면 true", example = "false")
            boolean owner,

            @Schema(description = "그룹에 들어온 때(UTC ISO-8601)", example = "2026-11-01T02:00:00Z")
            Instant joinedAt,

            @Schema(description = "앱에서 마지막으로 공부한 때(UTC ISO-8601) — 공부 기록이 없으면 null", example = "2026-11-03T05:30:00Z")
            Instant lastStudiedAt) {}
}
