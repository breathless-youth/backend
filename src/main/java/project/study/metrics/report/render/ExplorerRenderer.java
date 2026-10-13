package project.study.metrics.report.render;

import java.util.ArrayList;
import java.util.List;
import project.study.metrics.report.Rate;
import project.study.metrics.report.analyzer.ExplorerSection;
import project.study.metrics.report.analyzer.ExplorerSection.RankedRule;
import project.study.metrics.report.rule.Exposure;
import project.study.metrics.report.rule.TwoByTwo;

/** 🔍 자동 탐색 스레드 (부록 A 스레드 5). */
public final class ExplorerRenderer {

    private ExplorerRenderer() {}

    public static String render(ExplorerSection section) {
        List<String> lines = new ArrayList<>();
        lines.add("🔍 남는 사람과 관련 깊은 첫 주 행동 Top 3");
        lines.add("가입 %s %d명 · 남은 사람 = 둘째 주에도 공부한 %d명"
                .formatted(
                        Fmt.range(section.cohortFrom(), section.cohortTo()), section.cohortSize(), section.retained()));
        lines.add("연관 = 0.4 이상 강함 / 0.3~0.4 보통 / 0.2~0.3 약함");
        lines.add("");
        if (section.top().isEmpty()) {
            lines.add("오늘은 조건을 통과한 규칙 없음(표본 부족)");
        }
        for (RankedRule rule : section.top()) {
            lines.add(head(rule));
            lines.add(detail(rule, section));
        }
        lines.add("");
        lines.add(tally(section));
        if (section.fixedRuleExposure() != Exposure.PASS) {
            lines.add("첫 주 3일 이상(고정 세그먼트)은 %s으로 빠짐"
                    .formatted(section.fixedRuleExposure().label()));
        }
        lines.add("⚠️ 상관관계이지 인과는 아님. 실험으로 확인할 후보");
        return String.join("\n", lines);
    }

    private static String head(RankedRule rule) {
        return "%d. %s%s — 연관 %s (%s) · %s"
                .formatted(
                        rule.rank(),
                        rule.rule().name(),
                        rule.fixed() ? " (고정 세그먼트)" : "",
                        rule.strength().label(),
                        Fmt.phi(rule.table().phi()),
                        Fmt.trend(rule));
    }

    private static String detail(RankedRule rule, ExplorerSection section) {
        TwoByTwo table = rule.table();
        return "   %d명 (%s) · 둘째 주에도 공부 %s vs 안 한 사람 %s · 남은 %d명 중 %d명이 했음"
                .formatted(
                        table.doers(),
                        Fmt.percent(Rate.of(table.doers(), section.cohortSize())),
                        Fmt.rate(table.doerRetention()),
                        Fmt.rate(table.nonDoerRetention()),
                        section.retained(),
                        table.a());
    }

    private static String tally(ExplorerSection section) {
        return "규칙 %d개 중 통과 %d개 · 제외: 사람 부족 %d · 너무 흔함 %d · 상관 약함 %d · Amplitude 미수집 %d"
                .formatted(
                        section.ruleCount(),
                        section.passed(),
                        count(section, Exposure.TOO_FEW),
                        count(section, Exposure.TOO_COMMON),
                        count(section, Exposure.WEAK),
                        count(section, Exposure.NOT_COLLECTED));
    }

    private static int count(ExplorerSection section, Exposure exposure) {
        return section.tally().getOrDefault(exposure, 0);
    }
}
