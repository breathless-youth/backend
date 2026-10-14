package project.study.metrics.report.render;

import static java.util.stream.Collectors.joining;
import static project.study.metrics.slack.SlackMessage.bold;

import project.study.metrics.report.Kst;
import project.study.metrics.report.Rate;
import project.study.metrics.report.analyzer.SignupSection;
import project.study.metrics.report.analyzer.SignupSection.Attribution;
import project.study.metrics.report.analyzer.SignupSection.HourBand;
import project.study.metrics.slack.SlackMessage;
import project.study.metrics.slack.SlackMessage.Builder;

/** 🕐 가입 스레드 (부록 A 스레드 1). */
public final class SignupRenderer {

    private SignupRenderer() {}

    public static SlackMessage render(SignupSection section) {
        Builder out = SlackMessage.builder("🕐 가입");
        if (section.yesterdayCount() == 0) {
            out.item("어제 가입 = ", bold(0), "명");
        } else {
            out.item(
                    "어제 가입 = ",
                    bold(section.yesterdayCount()),
                    "명 (#%d~#%d)".formatted(section.minUserId(), section.maxUserId()));
            out.item(
                    "가입하고 3시간 안에 공부 = ",
                    bold(Fmt.percent(section.yesterdayQuick())),
                    Fmt.fraction(section.yesterdayQuick()));
        }
        String week = "최근 7일(%s) 가입 ".formatted(Fmt.range(section.weekFrom(), section.anchor()));
        if (section.weekTotal() == 0) {
            return out.item(week, bold(0), "명").build();
        }
        out.item(week, bold(section.weekTotal()), "명, 시간대별");
        int max = section.bands().stream().mapToInt(HourBand::signups).max().orElse(0);
        section.bands().forEach(band -> out.sub(band(band, max)));
        out.quote(peaks(section));
        out.item("공부 분야 (최근 7일 가입)").sub(categories(section));
        attribution(out, section.attribution());
        return out.build();
    }

    private static String band(HourBand band, int max) {
        return "%s %s %d명%s"
                .formatted(Kst.bandLabel(band.startHour()), Fmt.bar(band.signups(), max), band.signups(), quick(band));
    }

    // 5명 미만 구간은 비율이 흔들려 분수만 쓴다
    private static String quick(HourBand band) {
        if (band.signups() == 0) {
            return "";
        }
        String fraction = band.quick() + "/" + band.signups();
        return band.signups() >= SignupSection.MIN_SIGNUPS_FOR_RATE
                ? " · 바로 공부 %d%% (%s)".formatted(band.quickRate().percent(), fraction)
                : " · 바로 공부 " + fraction;
    }

    private static String peaks(SignupSection section) {
        String peak =
                "가입이 가장 많은 시간 " + Kst.bandLabel(section.peak().orElseThrow().startHour());
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

    private static void attribution(Builder out, Attribution attribution) {
        out.item("유입 (최근 7일 가입, Amplitude 기준)");
        if (attribution.missing() == attribution.total()) {
            out.sub("미수집 = %d명".formatted(attribution.missing()));
            return;
        }
        // 유입 정보가 있는 사람 중 비율 — 미수집은 끝에 따로 센다
        Rate adClicks = Rate.of(attribution.adClicks(), attribution.total() - attribution.missing());
        out.sub("광고 클릭 = ", bold(Fmt.percent(adClicks)), Fmt.fraction(adClicks));
        if (!attribution.topUtm().isEmpty()) {
            out.sub("UTM 상위 = "
                    + attribution.topUtm().stream()
                            .map(share -> share.label() + " " + share.count())
                            .collect(joining(" · ")));
        }
        if (attribution.missing() > 0) {
            out.sub("미수집 = %d명".formatted(attribution.missing()));
        }
    }
}
