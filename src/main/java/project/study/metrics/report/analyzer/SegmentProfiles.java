package project.study.metrics.report.analyzer;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import project.study.metrics.report.Attributions;
import project.study.metrics.report.Categories;
import project.study.metrics.report.Kst;
import project.study.metrics.report.OsLabels;
import project.study.metrics.report.Rate;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.analyzer.SegmentSection.Comparison;
import project.study.metrics.report.analyzer.SegmentSection.ShareRow;

/** "어떤 사람인가" — 3일 이상과 나머지의 분야·가입 시간대·OS·광고 유입 비율을 나란히 (스펙 §4.3). */
final class SegmentProfiles {

    private static final int TOP_CATEGORIES = 3;
    private static final int TOP_BANDS = 2;
    private static final int HOURS_PER_BAND = 3;
    private static final String OTHER_OS = "기타";
    private static final List<String> OS_ORDER = List.of("iOS", "Android", OTHER_OS, OsLabels.MISSING);

    private SegmentProfiles() {}

    static List<Comparison> compare(List<UserTimeline> segment, List<UserTimeline> rest) {
        return List.of(
                categories(segment, rest),
                bands(segment, rest),
                os(segment, rest),
                new Comparison("광고 유입", List.of(new ShareRow("", adRate(segment), adRate(rest)))));
    }

    // 3일 이상에서 많은 분야 3개 + 미입력
    private static Comparison categories(List<UserTimeline> segment, List<UserTimeline> rest) {
        Function<UserTimeline, String> label = user -> Categories.label(user.category());
        Map<String, Long> counts = segment.stream().collect(groupingBy(label, counting()));
        List<String> labels = new ArrayList<>(counts.keySet().stream()
                .filter(name -> !Categories.NONE.equals(name))
                .sorted(Comparator.comparing((String name) -> counts.get(name), Comparator.reverseOrder())
                        .thenComparingInt(Categories::order))
                .limit(TOP_CATEGORIES)
                .toList());
        labels.add(Categories.NONE);
        return new Comparison(
                "분야",
                labels.stream()
                        .map(name -> row(
                                name, segment, rest, user -> label.apply(user).equals(name)))
                        .toList());
    }

    private static Comparison bands(List<UserTimeline> segment, List<UserTimeline> rest) {
        Function<UserTimeline, Integer> band = user -> Kst.hour(user.signupAt()) / HOURS_PER_BAND;
        Map<Integer, Long> counts = segment.stream().collect(groupingBy(band, counting()));
        List<ShareRow> rows = counts.keySet().stream()
                .sorted(Comparator.comparing((Integer key) -> counts.get(key), Comparator.reverseOrder())
                        .thenComparing(Comparator.naturalOrder()))
                .limit(TOP_BANDS)
                .map(key -> row(
                        Kst.bandLabel(key * HOURS_PER_BAND),
                        segment,
                        rest,
                        user -> band.apply(user).equals(key)))
                .toList();
        return new Comparison("가입 시간대", rows);
    }

    private static Comparison os(List<UserTimeline> segment, List<UserTimeline> rest) {
        Set<String> present = Stream.concat(segment.stream(), rest.stream())
                .map(SegmentProfiles::osGroup)
                .collect(toSet());
        return new Comparison(
                "OS",
                OS_ORDER.stream()
                        .filter(present::contains)
                        .map(name ->
                                row(name, segment, rest, user -> osGroup(user).equals(name)))
                        .toList());
    }

    private static String osGroup(UserTimeline user) {
        String os = user.attribute()
                .map(attribute -> OsLabels.label(attribute.osName()))
                .orElse(null);
        if (os == null) {
            return OsLabels.MISSING;
        }
        return OS_ORDER.contains(os) ? os : OTHER_OS;
    }

    // 유입 정보(Amplitude 속성)가 있는 사람 중 비율 — 미수집을 "광고 아님"으로 세면 수집 장애가 유입 특성처럼 보인다
    private static Rate adRate(List<UserTimeline> users) {
        List<UserTimeline> known =
                users.stream().filter(user -> user.attribute().isPresent()).toList();
        return SegmentAnalyzer.rate(known, Attributions::adClick);
    }

    private static ShareRow row(
            String label, List<UserTimeline> segment, List<UserTimeline> rest, Predicate<UserTimeline> test) {
        return new ShareRow(label, SegmentAnalyzer.rate(segment, test), SegmentAnalyzer.rate(rest, test));
    }
}
