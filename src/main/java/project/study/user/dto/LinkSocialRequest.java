package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import project.study.user.entity.Provider;

public record LinkSocialRequest(
        @Schema(
                description = "소셜 로그인 프로바이더",
                example = "GOOGLE",
                allowableValues = {"GOOGLE", "KAKAO", "APPLE"})
        @NotNull
        Provider provider,

        @Schema(description = "프로바이더 SDK가 준 ID 토큰", example = "eyJhbGciOiJSUzI1NiIsImtpZCI6...")
        @NotBlank
        // 프로바이더 ID 토큰은 1~2KB다 — 임의 길이 입력이 검증까지 흘러들지 않게 상한을 둔다
        @Size(max = 4096, message = "ID 토큰은 4096자 이하여야 합니다")
        String idToken,

        @Schema(
                description = "앱이 만든 nonce 원문. SDK에는 이 값의 SHA-256 소문자 hex를 넘긴다. 애플은 필수, 구글·카카오는 SDK에 넘겼을 때만 싣는다",
                example = "3f9c2a8e-6b1d-4c5e-9a7f-0d2e4b6c8a1f")
        @Size(max = 128, message = "nonce는 128자 이하여야 합니다")
        String nonce) {

    // ID 토큰·nonce가 로그·예외 메시지로 새지 않게 record 기본 toString을 막는다
    @Override
    public String toString() {
        return "LinkSocialRequest[provider=" + provider + "]";
    }
}
