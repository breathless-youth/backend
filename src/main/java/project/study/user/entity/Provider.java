package project.study.user.entity;

public enum Provider {
    GOOGLE,
    /** 익명 기기 유저 (ADR-0004) — 로그인 재도입 전까지 기기 UUID로 식별한다. */
    DEVICE
}
