package project.study.metrics.report;

import java.util.Optional;
import project.study.metrics.amplitude.AmplitudeAttribute;

/** 카드의 유입 라벨 (스펙 §4.4) — 광고 클릭 > 가입 이틀 안 초대 링크 > UTM > 표시 없음. Amplitude 속성이 없으면 미수집. */
public final class Attributions {

    private Attributions() {}

    public static String label(UserTimeline user) {
        Optional<AmplitudeAttribute> attribute = user.attribute();
        if (attribute.isEmpty()) {
            return "유입 미수집";
        }
        String utm = attribute.get().utmSource();
        String source = utm == null || utm.isBlank() ? null : utm.strip();
        if (attribute.get().adClick()) {
            return "유입: 광고(" + (source == null ? "소스 없음" : source) + ")";
        }
        if (user.amplitudeCount(AmplitudeKeys.INVITE_OPENED, user.day(0), user.day(1)) > 0) {
            return "유입: 초대 링크";
        }
        return source == null ? "유입: 표시 없음" : "유입: " + source;
    }

    public static boolean adClick(UserTimeline user) {
        return user.attribute().map(AmplitudeAttribute::adClick).orElse(false);
    }
}
