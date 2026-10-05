package project.study.interview.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 완료 화면 카드와 설정 입구의 표시 여부·링크. 꺼진 쪽의 링크는 null이다. */
public record InterviewStatusResponse(
        @Schema(description = "완료 화면 카드를 띄울 대상인지 — 3번 그룹이고 전체·카드가 모두 켜져 있을 때 true", example = "true")
        boolean cardEligible,

        @Schema(
                description = "카드의 구글폼 링크. 앱이 NICKNAME 자리에 URL 인코딩한 닉네임을 넣는다. cardEligible이 false면 null",
                example = "https://docs.google.com/forms/d/e/.../viewform?usp=pp_url&entry.1606714956=NICKNAME")
        String cardUrl,

        @Schema(description = "설정의 '인터뷰 신청하기' 행을 보일지 — 그룹과 무관, 전체·설정이 모두 켜져 있을 때 true", example = "true")
        boolean settingsEnabled,

        @Schema(
                description =
                        "설정 행의 구글폼 링크(카드·공지와 같은 링크). 앱이 NICKNAME 자리에 URL 인코딩한 닉네임을 넣는다. settingsEnabled가 false면 null",
                example = "https://docs.google.com/forms/d/e/.../viewform?usp=pp_url&entry.1606714956=NICKNAME")
        String settingsUrl) {}
