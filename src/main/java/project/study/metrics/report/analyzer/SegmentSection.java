package project.study.metrics.report.analyzer;

import java.time.LocalDate;
import java.util.List;
import project.study.metrics.report.Rate;
import project.study.metrics.report.rule.TwoByTwo;

/**
 * 👥 첫 주 공부 일수별 비교 (스펙 §4.3). {@code table}은 "3일 이상 vs 나머지"의 2×2 표다.
 *
 * @param enoughForBehaviors 3일 이상이 5명 이상이라 행동·특징 비교를 하는지
 * @param amplitudeCollected 코호트 첫 주 기간 Amplitude가 다 수집돼 앱 행동도 후보에 넣었는지
 * @param profile 6번째 줄 "프로필(분야·목표) 입력" — 입력 시점 기록이 없어 지금 기준이다
 */
public record SegmentSection(
        LocalDate cohortFrom,
        LocalDate cohortTo,
        int cohortSize,
        List<GroupRow> groups,
        TwoByTwo table,
        boolean enoughForBehaviors,
        boolean amplitudeCollected,
        List<BehaviorRow> behaviors,
        BehaviorRow profile,
        List<Comparison> who) {

    public record GroupRow(String label, int size, Rate retained) {}

    public record BehaviorRow(String label, Rate segment, Rate rest) {}

    public record ShareRow(String label, Rate segment, Rate rest) {}

    public record Comparison(String title, List<ShareRow> rows) {}
}
