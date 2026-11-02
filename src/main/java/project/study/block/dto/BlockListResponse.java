package project.study.block.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** 내가 차단한 사람 목록 — 차단은 그룹과 상관없이 사용자 사이에 걸려, 어느 그룹 공부방에서든 서로 보이지 않는다. */
public record BlockListResponse(
        @Schema(description = "내가 차단한 사람 — 차단한 순서(오래된 것부터)") List<BlockedUser> blockedUsers) {

    public record BlockedUser(
            @Schema(description = "차단한 사람 userId — 차단 해제에 쓴다", example = "42")
            long userId,

            @Schema(description = "닉네임", example = "새벽형인간") String nickname,
            @Schema(description = "프로필 이미지 URL — 없으면 null") String profileImageUrl,

            @Schema(description = "차단한 때(UTC ISO-8601)", example = "2026-11-03T05:30:00Z")
            Instant blockedAt) {}
}
