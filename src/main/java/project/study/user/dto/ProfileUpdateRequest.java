package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ProfileUpdateRequest(
        @Schema(description = "변경할 닉네임 — 2~12자, 한글·영문·숫자만. 생략하면 미변경", example = "숨벅찬청년들")
        String nickname,

        @Schema(description = "변경할 한줄 목표 — 공백 포함 20자 이하. 생략하면 미변경", example = "올해 안에 이직 성공")
        String goal,

        @Schema(description = "변경할 카테고리. 생략하면 미변경", example = "JOB")
        String category) {}
