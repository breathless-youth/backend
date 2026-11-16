package project.study.user.entity;

public enum Provider {
    GOOGLE,
    KAKAO,
    APPLE,
    DEVICE,
    /** 스토어 심사 계정 (BY-1300, ADR-0048). 아이디·비밀번호로만 들어오고 계정은 하나다. */
    REVIEW
}
