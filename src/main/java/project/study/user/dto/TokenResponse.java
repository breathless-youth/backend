package project.study.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

public record TokenResponse(
        @Schema(description = "새 access 토큰(JWT, 30분)") String accessToken,

        // 쿠키 모드(브라우저)에서는 쿠키로만 주므로 필드째 뺀다
        @Schema(description = "새 refresh 토큰(UUID, 30일). 기존 토큰은 무효화됨. 쿠키 모드면 없다")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String refreshToken) {}
