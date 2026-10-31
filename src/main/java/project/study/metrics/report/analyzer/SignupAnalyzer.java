package project.study.metrics.report.analyzer;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import project.study.metrics.amplitude.AmplitudeAttribute;
import project.study.metrics.report.Categories;
import project.study.metrics.report.Kst;
import project.study.metrics.report.Rate;
import project.study.metrics.report.ReportData;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.analyzer.SignupSection.Attribution;
import project.study.metrics.report.analyzer.SignupSection.HourBand;
import project.study.metrics.report.analyzer.SignupSection.Share;
import project.study.studysession.StudySessionThresholds;

/** 🕐 가입 (스펙 §4.1). */
public final class SignupAnalyzer {

    /**
     * 바로 공부 = 가입 시각부터 3시간 안에 시작한 세션 순공 합 5분 이상. "가입한 날 공부"로 세면 23시 가입자는 기회가 1시간이라
     * 시간대 비교가 기울고, 3시간 창은 어제 가입자도 리포트(10:00) 전에 모두 닫힌다.
     */
    static final Duration QUICK_WINDOW = Duration.ofHours(3);

    private static final int BANDS = 8;
    private static final int HOURS_PER_BAND = 3;
    private static final int TOP_UTM = 3;

    private SignupAnalyzer() {}

    public static SignupSection analyze(ReportData data) {
        LocalDate anchor = data.anchor();
        List<UserTimeline> yesterday = data.signedUpBetween(anchor, anchor);
        List<UserTimeline> week = data.signedUpBetween(anchor.minusDays(6), anchor);
        LongSummaryStatistics ids =
                yesterday.stream().mapToLong(UserTimeline::userId).summaryStatistics();
        int quick = (int) yesterday.stream().filter(SignupAnalyzer::quickStudy).count();
        return new SignupSection(
                anchor,
                yesterday.size(),
                yesterday.isEmpty() ? null : ids.getMin(),
                yesterday.isEmpty() ? null : ids.getMax(),
                Rate.of(quick, yesterday.size()),
                week.size(),
                bands(week),
                categories(week),
                attribution(week));
    }

    public static boolean quickStudy(UserTimeline user) {
        return user.focusStartedWithin(QUICK_WINDOW) >= StudySessionThresholds.MIN_STREAK_FOCUS_SEC;
    }

    private static List<HourBand> bands(List<UserTimeline> users) {
        int[] signups = new int[BANDS];
        int[] quick = new int[BANDS];
        for (UserTimeline user : users) {
            int band = Kst.hour(user.signupAt()) / HOURS_PER_BAND;
            signups[band]++;
            if (quickStudy(user)) {
                quick[band]++;
            }
        }
        return IntStream.range(0, BANDS)
                .mapToObj(band -> new HourBand(band * HOURS_PER_BAND, signups[band], quick[band]))
                .toList();
    }

    // 수 내림차순, 같으면 분야 정의 순, 미입력은 맨 끝
    private static List<Share> categories(List<UserTimeline> users) {
        Map<String, Integer> counts = new HashMap<>();
        users.forEach(user -> counts.merge(Categories.label(user.category()), 1, Integer::sum));
        return counts.entrySet().stream()
                .map(entry -> new Share(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing((Share share) -> Categories.NONE.equals(share.label()))
                        .thenComparing(Share::count, Comparator.reverseOrder())
                        .thenComparingInt(share -> Categories.order(share.label())))
                .toList();
    }

    private static Attribution attribution(List<UserTimeline> users) {
        int missing = 0;
        int adClicks = 0;
        Map<String, Integer> utm = new HashMap<>();
        for (UserTimeline user : users) {
            Optional<AmplitudeAttribute> attribute = user.attribute();
            if (attribute.isEmpty()) {
                missing++;
                continue;
            }
            adClicks += attribute.get().adClick() ? 1 : 0;
            String source = attribute.get().utmSource();
            if (source != null && !source.isBlank()) {
                utm.merge(source.strip(), 1, Integer::sum);
            }
        }
        List<Share> top = utm.entrySet().stream()
                .map(entry -> new Share(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(Share::count, Comparator.reverseOrder())
                        .thenComparing(Share::label))
                .limit(TOP_UTM)
                .toList();
        return new Attribution(users.size(), adClicks, top, missing);
    }
}
