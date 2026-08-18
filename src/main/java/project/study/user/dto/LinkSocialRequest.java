package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import project.study.user.entity.Provider;

public record LinkSocialRequest(
        @Schema(description = "연동할 소셜 로그인 프로바이더", example = "GOOGLE") @NotNull
        Provider provider,

        @Schema(description = "소셜 SDK로 받은 ID 토큰", example = "eyJhbGciOiJSUzI1NiIsImtpZCI6...")
        @NotBlank
        // 정상 ID 토큰(JWT)은 수 KB — 임의 길이 입력이 프로바이더 검증 호출까지 흘러들지 않게 상한을 둔다
        @Size(max = 8192, message = "ID 토큰은 8192자 이하여야 합니다")
        String idToken) {}
