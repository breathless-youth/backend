package project.study.metrics.report.analyzer;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import project.study.metrics.report.rule.Exposure;
import project.study.metrics.report.rule.RuleCatalog;
import project.study.metrics.report.rule.SegmentRule;
import project.study.metrics.report.rule.Strength;
import project.study.metrics.report.rule.TwoByTwo;

/**
 * 🔍 자동 탐색 (스펙 §4.5).
 *
 * @param retained 코호트 중 둘째 주에도 공부한 사람
 * @param tally 규칙 40개의 판정별 개수
 * @param fixedRuleExposure 고정 규칙(첫 주 3일 이상)의 판정 — PASS가 아니면 꼬리에 빠진 이유를 쓴다
 */
public record ExplorerSection(
        LocalDate cohortFrom,
        LocalDate cohortTo,
        int cohortSize,
        int retained,
        List<RankedRule> top,
        Map<Exposure, Integer> tally,
        Exposure fixedRuleExposure) {

    /** @param days 오늘 포함 연속 일수 — 1위면 1위 연속, 2~3위면 Top 3 연속 (최대 3) */
    public record RankedRule(SegmentRule rule, TwoByTwo table, int rank, int days) {
        public boolean fixed() {
            return RuleCatalog.fixed(rule);
        }

        public Strength strength() {
            return Strength.of(table.phi());
        }
    }

    public int passed() {
        return tally.getOrDefault(Exposure.PASS, 0);
    }

    public int ruleCount() {
        return tally.values().stream().mapToInt(Integer::intValue).sum();
    }
}
