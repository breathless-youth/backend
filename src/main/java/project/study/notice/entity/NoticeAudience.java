package project.study.notice.entity;

import project.study.interview.InterviewGroup;

/**
 * 공지를 받을 사람 (ADR-0027). ALL이 아닌 대상은 인터뷰 모집 공지로 보고, 인터뷰 전체를 끄면 함께 빠진다.
 */
public enum NoticeAudience {
    ALL(null),
    G1_NOT_STARTED(InterviewGroup.G1_NOT_STARTED),
    G2_LAPSED(InterviewGroup.G2_LAPSED);

    private final InterviewGroup group;

    NoticeAudience(InterviewGroup group) {
        this.group = group;
    }

    public boolean isInterview() {
        return group != null;
    }

    public boolean includes(InterviewGroup userGroup) {
        return group == null || group == userGroup;
    }
}
