package project.study.interview.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 완료 화면 카드와 설정 입구의 표시 여부·링크. 꺼진 쪽의 링크는 null이다. */
public record InterviewStatusResponse(
        @Schema(description = "완료 화면 카드를 띄울 대상인지 — 3번 그룹이고 전체·카드가 모두 켜져 있을 때 true", example = "true")
        boolean cardEligible,

        @Schema(
                description = "카드의 구글폼 링크(source=g3_complete). cardEligible이 false면 null",
                example = "https://docs.google.com/forms/d/e/.../viewform?entry.1=g3_complete&entry.2=USER_ID")
        String cardUrl,

        @Schema(description = "설정의 '인터뷰 신청하기' 행을 보일지 — 그룹과 무관, 전체·설정이 모두 켜져 있을 때 true", example = "true")
        boolean settingsEnabled,

        @Schema(
                description = "설정 행의 구글폼 링크(source=settings). settingsEnabled가 false면 null",
                example = "https://docs.google.com/forms/d/e/.../viewform?entry.1=settings&entry.2=USER_ID")
        String settingsUrl) {}
