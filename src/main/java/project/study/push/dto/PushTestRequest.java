package project.study.push.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 테스트 발송 요청 — 모두 생략할 수 있고, 빠진 값은 기본 문구로 채운다. */
public record PushTestRequest(
        @Schema(description = "제목", example = "테스트 알림") String title,

        @Schema(description = "본문", example = "서버 발송 확인용 알림입니다")
        String body,

        @Schema(description = "탭하면 열 경로(스킴 없이)", example = "/records")
        String link) {

    // 기기 언어와 상관없이 입력한 문구가 그대로 보여야 확인이 되므로 두 언어에 같은 문구를 넣는다
    public PushMessage toMessage() {
        PushText text = new PushText(isBlank(title) ? "테스트 알림" : title, isBlank(body) ? "서버 발송 확인용 알림입니다" : body);
        return new PushMessage(text, text, isBlank(link) ? "/" : link, "test");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
