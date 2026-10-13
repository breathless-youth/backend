package project.study.metrics.report.analyzer;

import java.time.LocalDate;
import java.util.List;

/** 🙋 첫 주 3일 이상 명단 (스펙 §4.4) — 요약의 "N명 중 k명" 뒤에 있는 사람 그대로다. */
public record RosterSection(
        LocalDate anchor, LocalDate cohortFrom, LocalDate cohortTo, RosterGroup retained, RosterGroup dropped) {

    /** @param omittedSignupOnOrBefore 카드 상한으로 빠진 사람 중 가장 최근 가입일, 빠진 사람이 없으면 null */
    public record RosterGroup(int total, List<RosterCard> cards, LocalDate omittedSignupOnOrBefore) {
        public int omitted() {
            return total - cards.size();
        }
    }

    public int total() {
        return retained.total() + dropped.total();
    }
}
