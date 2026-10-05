package project.study.notice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import project.study.notice.entity.Notice;
import project.study.notice.entity.NoticeAudience;

/** 노출 기간(starts_at·ends_at)과 on/off는 운영 정보라 클라이언트에 내려보내지 않는다. */
public record NoticeResponse(
        @Schema(description = "공지 ID — '다시 보지 않기' 기록의 키", example = "1")
        Long id,

        @Schema(description = "제목", example = "그동안 불편했던 점을 들려주세요")
        String title,

        @Schema(description = "본문", example = "15분 통화면 충분해요. 일정은 편한 시간으로 맞춰드려요.")
        String content,

        @Schema(description = "이미지 URL, 없으면 null") String imageUrl,

        @Schema(
                description = "대상 — ALL(전원), G1_NOT_STARTED(인터뷰 1번), G2_LAPSED(인터뷰 2번). "
                        + "앱은 이 값으로 인터뷰 source(g1_revisit·g2_return)를 정한다",
                example = "G2_LAPSED")
        NoticeAudience audience,

        @Schema(description = "배지 문구, 없으면 null", example = "스타벅스 기프티콘 100% 증정")
        String badgeText,

        @Schema(description = "버튼 문구, 없으면 null(buttonUrl과 함께 있거나 함께 없다)", example = "인터뷰 신청하기")
        String buttonText,

        @Schema(description = "버튼 링크, 없으면 null. 인터뷰 공지는 사용자 ID 자리를 앱이 채운다")
        String buttonUrl) {

    public static NoticeResponse from(Notice notice) {
        return new NoticeResponse(
                notice.getId(),
                notice.getTitle(),
                notice.getContent(),
                notice.getImageUrl(),
                notice.getAudience(),
                notice.getBadgeText(),
                notice.getButtonText(),
                notice.getButtonUrl());
    }
}
