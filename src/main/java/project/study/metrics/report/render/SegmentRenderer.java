package project.study.metrics.report.render;

import static java.util.stream.Collectors.joining;

import java.util.ArrayList;
import java.util.List;
import project.study.metrics.report.Rate;
import project.study.metrics.report.analyzer.SegmentSection;
import project.study.metrics.report.analyzer.SegmentSection.BehaviorRow;
import project.study.metrics.report.analyzer.SegmentSection.Comparison;
import project.study.metrics.report.analyzer.SegmentSection.GroupRow;
import project.study.metrics.report.analyzer.SegmentSection.ShareRow;
import project.study.metrics.report.rule.Strength;
import project.study.metrics.report.rule.TwoByTwo;

/** 👥 첫 주 공부 일수별 비교 스레드 (부록 A 스레드 3). */
public final class SegmentRenderer {

    private static final int GROUP_LABEL_WIDTH = 16;
    private static final int PEOPLE_HEADER_WIDTH = 14;
    private static final int SIZE_WIDTH = 5;
    private static final int SHARE_WIDTH = 8;
    private static final int BEHAVIOR_WIDTH = 26;

    private SegmentRenderer() {}

    public static String render(SegmentSection section) {
        List<String> lines = new ArrayList<>();
        lines.add("👥 첫 주에 며칠 공부했나 → 둘째 주에도 공부했나");
        lines.add("가입 %s %d명 · 첫 주 = 가입일 포함 7일 · 둘째 주 = 가입 7~13일 뒤"
                .formatted(Fmt.range(section.cohortFrom(), section.cohortTo()), section.cohortSize()));
        lines.add(Fmt.code(groupTable(section)));
        lines.add(comparison(section.table()));
        lines.add("");
        if (!section.enoughForBehaviors()) {
            lines.add("3일 이상이 %d명이라 행동·특징 비교는 생략 (5명 미만, 표본 부족)"
                    .formatted(section.table().doers()));
            return String.join("\n", lines);
        }
        lines.add("3일 이상인 사람이 첫 주에 주로 한 행동 (3일 이상 %d명 / 나머지 %d명)%s"
                .formatted(
                        section.table().doers(),
                        section.table().nonDoers(),
                        section.amplitudeCollected() ? "" : " · Amplitude 미수집 기간이라 앱 행동은 뺌"));
        lines.add(Fmt.code(behaviors(section)));
        lines.add("");
        lines.add("어떤 사람인가 (3일 이상 / 나머지)");
        section.who().forEach(comparison -> lines.add("• " + comparisonLine(comparison)));
        return String.join("\n", lines);
    }

    private static List<String> groupTable(SegmentSection section) {
        List<String> rows = new ArrayList<>();
        rows.add(Fmt.padRight("첫 주 공부한 날", GROUP_LABEL_WIDTH) + Fmt.padRight("사람", PEOPLE_HEADER_WIDTH) + "둘째 주에도 공부");
        for (GroupRow group : section.groups()) {
            String share = "(" + Fmt.percent(Rate.of(group.size(), section.cohortSize())) + ")";
            rows.add(Fmt.padRight(group.label(), GROUP_LABEL_WIDTH)
                    + Fmt.padLeft(group.size() + "명", SIZE_WIDTH)
                    + " "
                    + Fmt.padRight(share, SHARE_WIDTH)
                    + " "
                    + Fmt.rate(group.retained()));
        }
        return rows;
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
        return "→ 3일 이상 vs 나머지: %s vs %s%s · %s".formatted(Fmt.percent(segment), Fmt.percent(rest), gap, link);
    }

    private static List<String> behaviors(SegmentSection section) {
        List<String> lines = new ArrayList<>();
        if (section.behaviors().isEmpty()) {
            lines.add("(3일 이상의 30% 이상이 한 행동 없음)");
        }
        int number = 1;
        for (BehaviorRow row : section.behaviors()) {
            lines.add(behaviorLine(number++, row));
        }
        lines.add(behaviorLine(number, section.profile()) + "   ※ 지금 기준 — 입력 시점은 기록이 없음");
        return lines;
    }

    // 이름이 길어도 퍼센트와 붙지 않게 공백을 하나는 둔다
    private static String behaviorLine(int number, BehaviorRow row) {
        return Fmt.padRight(number + ". " + row.label() + " ", BEHAVIOR_WIDTH)
                + Fmt.percent(row.segment())
                + " / "
                + Fmt.percent(row.rest());
    }

    private static String comparisonLine(Comparison comparison) {
        List<ShareRow> rows = comparison.rows();
        if (rows.size() == 1 && rows.getFirst().label().isEmpty()) {
            return comparison.title() + ": " + pair(rows.getFirst());
        }
        return comparison.title() + ": "
                + rows.stream().map(row -> row.label() + " " + pair(row)).collect(joining(" · "));
    }

    private static String pair(ShareRow row) {
        return Fmt.percent(row.segment()) + " / " + Fmt.percent(row.rest());
    }
}
