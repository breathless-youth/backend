package project.study.notice.dto;

import project.study.notice.entity.Notice;

/** 노출 기간(starts_at·ends_at)은 운영 정보라 클라이언트에 내려보내지 않는다. */
public record NoticeResponse(Long id, String title, String content, String imageUrl) {

    public static NoticeResponse from(Notice notice) {
        return new NoticeResponse(notice.getId(), notice.getTitle(), notice.getContent(), notice.getImageUrl());
    }
}
