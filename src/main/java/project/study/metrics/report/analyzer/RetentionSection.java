package project.study.metrics.report.analyzer;

import java.time.LocalDate;
import java.util.List;
import project.study.metrics.report.Rate;
import project.study.metrics.report.analyzer.RetentionAnalyzer.Window;

/** 📈 리텐션 (스펙 §4.2). 행 순서는 다음 날 → 7~13일 뒤 → 28~34일 뒤. */
public record RetentionSection(List<RetentionRow> rows, Rate firstWeekNone, LocalDate noneFrom, LocalDate noneTo) {

    /** @param lastWeek 같은 계산을 기준일 A−7로 한 값 */
    public record RetentionRow(Window window, Rate current, Rate lastWeek, LocalDate cohortFrom, LocalDate cohortTo) {}
}
