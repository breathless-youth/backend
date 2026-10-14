package project.study.metrics.report.render;

import static java.util.stream.Collectors.joining;
import static project.study.metrics.slack.SlackMessage.bold;

import java.util.List;
import project.study.metrics.report.Rate;
import project.study.metrics.report.analyzer.SegmentSection;
import project.study.metrics.report.analyzer.SegmentSection.BehaviorRow;
import project.study.metrics.report.analyzer.SegmentSection.Comparison;
import project.study.metrics.report.analyzer.SegmentSection.GroupRow;
import project.study.metrics.report.analyzer.SegmentSection.ShareRow;
import project.study.metrics.report.rule.Strength;
import project.study.metrics.report.rule.TwoByTwo;
import project.study.metrics.slack.SlackMessage;
import project.study.metrics.slack.SlackMessage.Builder;

/** 👥 첫 주 공부 일수별 비교 스레드 (부록 A 스레드 3). */
public final class SegmentRenderer {

    private SegmentRenderer() {}

    public static SlackMessage render(SegmentSection section) {
        Builder out = SlackMessage.builder("👥 첫 주에 며칠 공부했나 → 둘째 주에도 공부했나");
        out.item(
                        "대상 = 가입 %s ".formatted(Fmt.range(section.cohortFrom(), section.cohortTo())),
                        bold(section.cohortSize()),
                        "명")
                .sub("첫 주 = 가입일 포함 7일 · 둘째 주 = 가입 7~13일 뒤");
        out.item("둘째 주에도 공부한 비율");
        for (GroupRow group : section.groups()) {
            String share = Fmt.percent(Rate.of(group.size(), section.cohortSize()));
            out.sub(
                    "%s %d명(%s) = ".formatted(group.label(), group.size(), share),
                    bold(Fmt.percent(group.retained())),
                    Fmt.fraction(group.retained()));
        }
        out.quote(comparison(section.table()));
        if (!section.enoughForBehaviors()) {
            return out.item("3일 이상이 %d명이라 행동·특징 비교는 생략 (5명 미만, 표본 부족)"
                            .formatted(section.table().doers()))
                    .build();
        }
        behaviors(out, section);
        out.item("어떤 사람인가 (3일 이상 / 나머지)");
        section.who().forEach(comparison -> out.sub(comparisonLine(comparison)));
        return out.build();
    }

    private static String comparison(TwoByTwo table) {
        Rate segment = table.doerRetention();
        Rate rest = table.nonDoerRetention();
        String gap =
                segment.defined() && rest.defined() ? " (%+d%%p)".formatted(segment.percent() - rest.percent()) : "";
        double phi = table.phi();
        String link = Double.isNaN(phi)
                ? "연관 —"
                : "연관 %s (%s)".formatted(Strength.of(phi).label(), Fmt.phi(phi));
        return "3일 이상 vs 나머지 = %s vs %s%s · %s".formatted(Fmt.percent(segment), Fmt.percent(rest), gap, link);
    }

    private static void behaviors(Builder out, SegmentSection section) {
        out.item("3일 이상인 사람이 첫 주에 주로 한 행동 (3일 이상 %d명 / 나머지 %d명)%s"
                .formatted(
                        section.table().doers(),
                        section.table().nonDoers(),
                        section.amplitudeCollected() ? "" : " · Amplitude 미수집 기간이라 앱 행동은 뺌"));
        if (section.behaviors().isEmpty()) {
            out.sub("3일 이상의 30% 이상이 한 행동 없음");
        }
        section.behaviors().forEach(row -> behavior(out, row, ""));
        behavior(out, section.profile(), " · 지금 기준(입력 시점은 기록 없음)");
    }

    private static void behavior(Builder out, BehaviorRow row, String note) {
        out.subNumbered(row.label() + " = ", bold(Fmt.percent(row.segment())), " / " + Fmt.percent(row.rest()) + note);
    }

    private static String comparisonLine(Comparison comparison) {
        List<ShareRow> rows = comparison.rows();
        if (rows.size() == 1 && rows.getFirst().label().isEmpty()) {
            return comparison.title() + " = " + pair(rows.getFirst());
        }
        return comparison.title() + " = "
                + rows.stream().map(row -> row.label() + " " + pair(row)).collect(joining(" · "));
    }

    private static String pair(ShareRow row) {
        return Fmt.percent(row.segment()) + " / " + Fmt.percent(row.rest());
    }
}
