package project.study.common;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 모든 에러 응답의 공통 본문.
 *
 * @param code 클라이언트가 분기에 쓰는 식별자 — 문구가 아니라 이 값으로 분기한다
 * @param message 사용자에게 그대로 보여줄 수 있는 안내 문구
 */
public record ErrorResponse(
        @Schema(description = "에러 식별자 — 클라이언트는 message가 아니라 이 값으로 분기한다", example = "ROOM_CLOSED")
        ErrorCode code,

        @Schema(description = "사용자에게 보여줄 안내 문구", example = "방이 종료되었어요")
        String message) {}
