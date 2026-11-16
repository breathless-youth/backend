package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReviewLoginRequest(
        @Schema(description = "심사 계정 아이디", example = "review") @NotBlank @Size(max = 64)
        String loginId,

        // 길이 검증을 여기 두지 않는다 — 검증 실패 메시지에 입력값이 실려 오류 수집에 비밀번호가 남는다. 길이는 서비스가 본다
        @Schema(description = "심사 계정 비밀번호") @NotBlank String password,

        @Schema(description = "앱 버전. 출시된 버전보다 높아야 로그인된다", example = "26.47.0") @NotBlank @Size(max = 32)
        String appVersion) {}
