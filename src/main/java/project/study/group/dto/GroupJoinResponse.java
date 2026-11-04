package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record GroupJoinResponse(
        @Schema(description = "들어간 그룹 ID — 이 그룹의 그룹 홈으로 보낸다", example = "10")
        long groupId,

        @Schema(description = "방금 멤버가 됐으면 true, 이미 멤버였으면 false — 둘 다 200이고 그룹 홈으로 보내면 된다", example = "true")
        boolean joined) {}
