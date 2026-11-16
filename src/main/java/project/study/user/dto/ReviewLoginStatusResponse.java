package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ReviewLoginStatusResponse(
        @Schema(description = "이 앱 버전에서 심사용 로그인이 켜져 있는지. false면 입구를 그리지 않는다")
        boolean enabled) {}
