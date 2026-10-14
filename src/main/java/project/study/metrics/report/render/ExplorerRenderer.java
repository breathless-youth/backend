package project.study.metrics.report.render;

import static project.study.metrics.slack.SlackMessage.bold;

import project.study.metrics.report.Rate;
import project.study.metrics.report.analyzer.ExplorerSection;
import project.study.metrics.report.analyzer.ExplorerSection.RankedRule;
import project.study.metrics.report.rule.Exposure;
import project.study.metrics.report.rule.TwoByTwo;
import project.study.metrics.slack.SlackMessage;
import project.study.metrics.slack.SlackMessage.Builder;

/** 🔍 자동 탐색 스레드 (부록 A 스레드 5). 규칙은 순위대로 번호 목록, 인원·비교는 하위 항목이다. */
public final class ExplorerRenderer {

    private ExplorerRenderer() {}

    public static SlackMessage render(ExplorerSection section) {
        Builder out = SlackMessage.builder("🔍 남는 사람과 관련 깊은 첫 주 행동 Top 3")
                .item("대상 = 가입 %s %d명 · 남은 사람 = 둘째 주에도 공부한 %d명"
                        .formatted(
                                Fmt.range(section.cohortFrom(), section.cohortTo()),
                                section.cohortSize(),
                                section.retained()))
                .sub("연관 = 0.4 이상 강함 / 0.3~0.4 보통 / 0.2~0.3 약함")
                .blank();
        if (section.top().isEmpty()) {
            out.item("오늘은 조건을 통과한 규칙 없음 (표본 부족)");
        }
        section.top().forEach(rule -> rule(out, rule, section));
        out.blank()
                .item("규칙 %d개 중 통과 %d개".formatted(section.ruleCount(), section.passed()))
                .sub(excluded(section));
        if (section.fixedRuleExposure() != Exposure.PASS) {
            out.sub("첫 주 3일 이상(고정 세그먼트)은 %s으로 빠짐"
                    .formatted(section.fixedRuleExposure().label()));
        }
        return out.quote("⚠️ 상관관계이지 인과는 아님. 실험으로 확인할 후보").build();
    }

    private static void rule(Builder out, RankedRule rule, ExplorerSection section) {
        TwoByTwo table = rule.table();
        out.numbered(
                        bold(rule.rule().name()),
                        rule.fixed() ? " (고정 세그먼트)" : "",
                        " — 연관 %s (%s) · %s".formatted(rule.strength().label(), Fmt.phi(table.phi()), Fmt.trend(rule)))
                .sub("%d명 (%s) · 남은 %d명 중 %d명이 했음"
                        .formatted(
                                table.doers(),
                                Fmt.percent(Rate.of(table.doers(), section.cohortSize())),
                                section.retained(),
                                table.a()))
                .sub(
                        "둘째 주에도 공부 = ",
                        bold(Fmt.percent(table.doerRetention())),
                        Fmt.fraction(table.doerRetention()),
                        " vs 안 한 사람 " + Fmt.rate(table.nonDoerRetention()));
    }

    private static String excluded(ExplorerSection section) {
        return "제외 = 사람 부족 %d · 너무 흔함 %d · 상관 약함 %d · Amplitude 미수집 %d"
                .formatted(
                        count(section, Exposure.TOO_FEW),
                        count(section, Exposure.TOO_COMMON),
                        count(section, Exposure.WEAK),
                        count(section, Exposure.NOT_COLLECTED));
    }

    private static int count(ExplorerSection section, Exposure exposure) {
        return section.tally().getOrDefault(exposure, 0);
    }
}
