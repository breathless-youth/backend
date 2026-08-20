package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ProfileResponse(
        @Schema(description = "닉네임 — 전역 유니크, 최초값은 등록 시 자동 발급(포메{랜덤4자리})", example = "포메3721")
        String nickname,

        @Schema(description = "한줄 목표 — 미설정 시 null (룸 타일에서 표시 생략)", example = "올해 안에 이직 성공")
        String goal,

        @Schema(description = "카테고리 — 미설정 시 null", example = "JOB")
        String category,

        @Schema(description = "아바타 이니셜 — 닉네임 첫 글자 (서버 산출)", example = "포")
        String initial,

        @Schema(description = "아바타 색상 인덱스 (0~7) — 최초 배정 후 불변", example = "3")
        Integer colorIndex) {}
