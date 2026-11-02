package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record GroupCreateResponse(
        @Schema(description = "만들어진 그룹 ID — 만든 사람은 멤버이자 그룹장이다", example = "10")
        long groupId) {}
