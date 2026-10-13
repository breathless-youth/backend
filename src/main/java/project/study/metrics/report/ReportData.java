package project.study.metrics.report;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 리포트 한 번에 쓰는 사실 전체.
 *
 * @param anchor 기준일 A(발송일 KST 어제)
 * @param amplitudeDates Amplitude 창 처리 기록이 있는 날
 * @param appOpenedOnAnchor A에 앱을 연(`app_launched`·`app_foregrounded`) 유저 — 제외 유저는 뺀 값
 */
public record ReportData(
        LocalDate anchor, List<UserTimeline> users, Set<LocalDate> amplitudeDates, Set<Long> appOpenedOnAnchor) {

    private static final Comparator<UserTimeline> SIGNUP_ORDER =
            Comparator.comparing(UserTimeline::signupAt).thenComparing(UserTimeline::userId);

    /** 가입일이 {@code [from, to]}인 유저, 가입 시각 순. */
    public List<UserTimeline> signedUpBetween(LocalDate from, LocalDate to) {
        return users.stream()
                .filter(user ->
                        !user.signupDate().isBefore(from) && !user.signupDate().isAfter(to))
                .sorted(SIGNUP_ORDER)
                .toList();
    }

    /** {@code [from, to]}의 모든 날에 Amplitude 창이 처리됐는지. */
    public boolean amplitudeCollected(LocalDate from, LocalDate to) {
        return from.datesUntil(to.plusDays(1)).allMatch(amplitudeDates::contains);
    }

    public List<UserTimeline> studiedOn(LocalDate date) {
        return users.stream().filter(user -> user.studied(date)).toList();
    }
}
