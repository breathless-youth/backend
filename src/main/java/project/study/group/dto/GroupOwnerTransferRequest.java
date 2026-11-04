package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record GroupOwnerTransferRequest(
        @Schema(
                description = "새 그룹장으로 넘길 멤버의 userId — 지금 이 그룹 멤버여야 한다. 아니면 404 `GROUP_MEMBER_NOT_FOUND`",
                example = "42")
        @NotNull
        Long userId) {}
