package project.study.metrics.report.analyzer;

import java.time.Instant;
import java.util.List;
import project.study.metrics.report.Kst;
import project.study.metrics.report.Rate;
import project.study.metrics.report.ReportData;
import project.study.metrics.report.analyzer.ExperimentSection.ArmStats;
import project.study.metrics.report.fact.ExperimentFact;

/** 🔔 첫 세션 5분 푸시 실험 (BY-1070, ADR-0037). 모든 지표는 기준일이 끝나는 시각까지의 값이다. */
public final class ExperimentAnalyzer {

    static final String ARM_PUSH = "PUSH";
    static final String ARM_CONTROL = "CONTROL";
    static final int FIRST_SESSION_TARGET_SEC = 300;

    /** Bounded D7 10% → 15%를 잡는 집단당 인원(유의수준 5%, 검정력 80%). */
    static final int TARGET_PER_ARM = 680;

    private ExperimentAnalyzer() {}

    public static ExperimentSection analyze(ReportData data) {
        if (data.experiment() == null) {
            throw new IllegalStateException("실험 배정 사실을 읽지 못했다 — 앞선 오류 로그 참고");
        }
        Instant cutoff = Kst.startOf(data.anchor().plusDays(1));
        List<ExperimentFact> facts = data.experiment().facts().stream()
                .filter(fact -> fact.assignedAt().isBefore(cutoff))
                .toList();
        return new ExperimentSection(
                data.experiment().d0Delay(),
                stats(facts, ARM_PUSH, cutoff),
                stats(facts, ARM_CONTROL, cutoff),
                TARGET_PER_ARM);
    }

    private static ArmStats stats(List<ExperimentFact> facts, String arm, Instant cutoff) {
        List<ExperimentFact> group =
                facts.stream().filter(fact -> arm.equals(fact.arm())).toList();
        int started = 0;
        int fiveMinutes = 0;
        int judged = 0;
        int returned = 0;
        int withToken = 0;
        int off = 0;
        for (ExperimentFact fact : group) {
            if (fact.firstSessionAt() != null && fact.firstSessionAt().isBefore(cutoff)) {
                started++;
                fiveMinutes += fact.firstFocusSec() >= FIRST_SESSION_TARGET_SEC ? 1 : 0;
            }
            if (fact.daySevenReturned() != null) {
                judged++;
                returned += fact.daySevenReturned() ? 1 : 0;
            }
            if (fact.hasToken()) {
                withToken++;
                off += fact.notificationsEnabled() ? 0 : 1;
            }
        }
        int size = group.size();
        return new ArmStats(
                size,
                Rate.of(started, size),
                Rate.of(fiveMinutes, size),
                Rate.of(returned, judged),
                Rate.of(off, withToken),
                size - withToken);
    }
}
