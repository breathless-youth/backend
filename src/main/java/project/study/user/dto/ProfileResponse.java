package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ProfileResponse(
        @Schema(
                description = "닉네임 — 전역 유니크, 영문 대소문자 무시. 최초값은 등록 시 휴대폰 언어 목록에서 자동 발급(꾸미는 말 + 동물, 예: 벼락치기하는 수달)",
                example = "벼락치기하는 수달")
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
        String profileImageUrl,

        @Schema(
                description = "로그인한 소셜 계정 — 비회원이면 null. 그룹 탭 진입(로그인 필요)과 탈퇴 방법(애플 재인증·카카오 연결 끊기)을 고르는 데 쓴다",
                example = "KAKAO",
                allowableValues = {"GOOGLE", "KAKAO", "APPLE"},
                nullable = true)
        String loginProvider) {}
