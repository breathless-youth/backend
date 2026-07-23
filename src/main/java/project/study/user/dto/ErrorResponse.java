package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "요청이 실패했을 때 내려가는 공통 에러 응답")
public record ErrorResponse(
        @Schema(description = "실패 사유를 사람이 읽을 수 있는 문장으로 담은 메시지", example = "유효하지 않은 refresh 토큰입니다")
        String error) {}
