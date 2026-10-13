package project.study.metrics.report.render;

import static java.util.stream.Collectors.joining;

import java.util.ArrayList;
import java.util.List;
import project.study.metrics.report.Kst;
import project.study.metrics.report.Rate;
import project.study.metrics.report.analyzer.SignupSection;
import project.study.metrics.report.analyzer.SignupSection.Attribution;
import project.study.metrics.report.analyzer.SignupSection.HourBand;

/** 🕐 가입 스레드 (부록 A 스레드 1). */
public final class SignupRenderer {

    private SignupRenderer() {}

    public static String render(SignupSection section) {
        List<String> lines = new ArrayList<>();
        lines.add("🕐 가입");
        if (section.yesterdayCount() == 0) {
            lines.add("어제 가입 0명");
        } else {
            lines.add("어제 가입 %d명 (#%d~#%d)"
                    .formatted(section.yesterdayCount(), section.minUserId(), section.maxUserId()));
            lines.add("가입하고 3시간 안에 공부한 사람: " + Fmt.rate(section.yesterdayQuick()));
        }
        lines.add("");
        String week =
                "최근 7일(%s) 가입 %d명".formatted(Fmt.range(section.weekFrom(), section.anchor()), section.weekTotal());
        if (section.weekTotal() == 0) {
            lines.add(week);
            return String.join("\n", lines);
        }
        lines.add(week + " — 시간대별 가입 · 그중 3시간 안에 공부");
        lines.add(Fmt.code(bars(section)));
        lines.add(peaks(section));
        lines.add("");
        lines.add("공부 분야 (최근 7일 가입): " + categories(section));
        lines.add("유입 (최근 7일 가입, Amplitude 기준): " + attribution(section.attribution()));
        return String.join("\n", lines);
    }

    private static List<String> bars(SignupSection section) {
        int max = section.bands().stream().mapToInt(HourBand::signups).max().orElse(0);
        return section.bands().stream()
                .map(band -> "%s  %s %3d%s"
                        .formatted(
                                Fmt.bandDash(band.startHour()),
                                Fmt.bar(band.signups(), max),
                                band.signups(),
                                quick(band)))
                .toList();
    }

    // 5명 미만 구간은 비율이 흔들려 분수만 쓴다
    private static String quick(HourBand band) {
        if (band.signups() == 0) {
            return "";
        }
        String fraction = band.quick() + "/" + band.signups();
        return band.signups() >= SignupSection.MIN_SIGNUPS_FOR_RATE
                ? "   바로 공부 %s (%d%%)".formatted(fraction, band.quickRate().percent())
                : "   바로 공부 " + fraction;
    }

    private static String peaks(SignupSection section) {
        String peak =
                "→ 가입이 가장 많은 시간 " + Kst.bandLabel(section.peak().orElseThrow().startHour());
        return section.bestQuick()
                .map(best -> peak
                        + " · 바로 공부 비율이 가장 높은 시간 %s (%d/%d)"
                                .formatted(Kst.bandLabel(best.startHour()), best.quick(), best.signups()))
                .orElse(peak);
    }

    private static String categories(SignupSection section) {
        return section.categories().stream()
                .map(share -> share.label() + " " + Fmt.percent(Rate.of(share.count(), section.weekTotal())))
                .collect(joining(" · "));
    }

    private static String attribution(Attribution attribution) {
        if (attribution.missing() == attribution.total()) {
            return "미수집 %d명".formatted(attribution.missing());
        }
        List<String> parts = new ArrayList<>();
        parts.add("광고 클릭 " + Fmt.rate(Rate.of(attribution.adClicks(), attribution.total())));
        if (!attribution.topUtm().isEmpty()) {
            parts.add("UTM 상위 "
                    + attribution.topUtm().stream()
                            .map(share -> share.label() + " " + share.count())
                            .collect(joining(" · ")));
        }
        if (attribution.missing() > 0) {
            parts.add("미수집 %d명".formatted(attribution.missing()));
        }
        return String.join(" · ", parts);
    }
}
