package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ProfileResponse(
        @Schema(description = "닉네임 — 전역 유니크, 최초값은 등록 시 자동 발급(포메{랜덤5자리})", example = "포메37210")
        String nickname,

        @Schema(description = "한줄 목표 — 미설정 시 null (룸 타일에서 표시 생략)", example = "올해 안에 이직 성공")
        String goal,

        @Schema(description = "카테고리 — 미설정 시 null", example = "JOB")
        String category,

        @Schema(description = "아바타 이니셜 — 닉네임 첫 글자 (서버 산출)", example = "포")
        String initial,

        @Schema(description = "아바타 색상 인덱스 (0~7) — 최초 배정 후 불변", example = "3")
        Integer colorIndex,

        @Schema(
                description = "프로필 사진 경로 — 없으면 null. 지금은 웹과 같은 출처의 상대 경로",
                example = "/avatars/12/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.jpg")
        String profileImageUrl) {}
