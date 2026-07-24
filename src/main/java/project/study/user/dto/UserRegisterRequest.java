package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UserRegisterRequest(
        @Schema(
                description = "앱이 첫 실행 때 생성해 기기 보안 저장소에 보관하는 기기 식별 UUID. 대소문자는 서버가 소문자로 통일한다",
                example = "0f8fad5b-d9cb-469f-a165-70867728950e")
        @NotBlank
        @Pattern(
                regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                message = "UUID 형식이어야 합니다")
        String deviceId) {}
