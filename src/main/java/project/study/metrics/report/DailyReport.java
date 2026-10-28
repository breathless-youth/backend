package project.study.metrics.report;

import java.time.LocalDate;
import project.study.metrics.report.analyzer.ActiveUserClassifier;
import project.study.metrics.report.analyzer.ActiveUsersSection;
import project.study.metrics.report.analyzer.ExperimentAnalyzer;
import project.study.metrics.report.analyzer.ExperimentSection;
import project.study.metrics.report.analyzer.ExplorerSection;
import project.study.metrics.report.analyzer.RetentionAnalyzer;
import project.study.metrics.report.analyzer.RetentionSection;
import project.study.metrics.report.analyzer.RosterSection;
import project.study.metrics.report.analyzer.RuleExplorer;
import project.study.metrics.report.analyzer.SegmentAnalyzer;
import project.study.metrics.report.analyzer.SegmentRoster;
import project.study.metrics.report.analyzer.SegmentSection;
import project.study.metrics.report.analyzer.SignupAnalyzer;
import project.study.metrics.report.analyzer.SignupSection;

/** 리포트 한 번의 섹션 일곱 개 — 스레드 순서와 같다 (스펙 §2 결정 8). */
public record DailyReport(
        LocalDate anchor,
        SectionResult<SignupSection> signup,
        SectionResult<RetentionSection> retention,
        SectionResult<SegmentSection> segment,
        SectionResult<RosterSection> roster,
        SectionResult<ExplorerSection> explorer,
        SectionResult<ActiveUsersSection> active,
        SectionResult<ExperimentSection> experiment) {

    public static DailyReport analyze(ReportData data) {
        return new DailyReport(
                data.anchor(),
                SectionResult.compute("가입", () -> SignupAnalyzer.analyze(data)),
                SectionResult.compute("리텐션", () -> RetentionAnalyzer.analyze(data)),
                SectionResult.compute("첫 주 비교", () -> SegmentAnalyzer.analyze(data)),
                SectionResult.compute("명단", () -> SegmentRoster.analyze(data)),
                SectionResult.compute("자동 탐색", () -> RuleExplorer.analyze(data)),
                SectionResult.compute("어제 공부한 유저", () -> ActiveUserClassifier.analyze(data)),
                SectionResult.compute("첫 세션 실험", () -> ExperimentAnalyzer.analyze(data)));
    }
}
