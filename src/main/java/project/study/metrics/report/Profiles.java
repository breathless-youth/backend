package project.study.metrics.report;

/** 카드·한 줄 프로필의 이름과 "분야(목표)" 라벨. 목표는 자유 입력이라 입력한 경우만 괄호로 붙인다 (스펙 §4.6). */
public final class Profiles {

    public static final String NO_NICKNAME = "닉네임 없음";
    public static final String NO_CATEGORY = "분야 미입력";

    private Profiles() {}

    public static String nickname(UserTimeline user) {
        return hasText(user.nickname()) ? user.nickname().strip() : NO_NICKNAME;
    }

    public static String label(UserTimeline user) {
        String category = hasText(user.category()) ? Categories.label(user.category()) : NO_CATEGORY;
        return hasText(user.goal()) ? category + "(" + user.goal().strip() + ")" : category;
    }

    /** 프로필 입력 = 분야·목표 중 하나라도. 입력 시점 기록이 없어 "지금 기준"이다. */
    public static boolean filled(UserTimeline user) {
        return hasText(user.category()) || hasText(user.goal());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
