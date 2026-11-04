package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record GroupNotificationRequest(
        @Schema(description = "멤버 공부 알림 받기 — 이 그룹 멤버가 공부를 시작하면 푸시로 알려 준다. 처음엔 꺼져 있다", example = "true") @NotNull
        Boolean enabled) {}
