package project.study.interview;

/** 인터뷰 모집의 {@code app_config} 키 (V23, ADR-0027). */
public final class InterviewConfigKeys {

    /** 전체 스위치 — 끄면 1·2번 공지, 완료 화면 카드, 설정 입구가 모두 빠진다. */
    public static final String ENABLED = "interview.enabled";

    public static final String CARD_ENABLED = "interview.card.enabled";
    public static final String CARD_URL = "interview.card.url";
    public static final String SETTINGS_ENABLED = "interview.settings.enabled";
    public static final String SETTINGS_URL = "interview.settings.url";

    private InterviewConfigKeys() {}
}
