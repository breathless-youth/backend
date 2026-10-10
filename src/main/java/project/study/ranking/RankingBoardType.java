package project.study.ranking;

import static project.study.ranking.RankingPeriod.DAILY;
import static project.study.ranking.RankingPeriod.MONTHLY;
import static project.study.ranking.RankingPeriod.WEEKLY;

import java.util.EnumSet;
import java.util.Set;

/** 랭킹 종목 (BY-828). 기간이 없는 셋(누적 시간·누적 일수·연속 공부 일수)이 명예의 전당이다. */
public enum RankingBoardType {
    FOCUS_TIME(EnumSet.of(DAILY, WEEKLY, MONTHLY)),
    FOCUS_RATE(EnumSet.of(WEEKLY, MONTHLY)),
    TIME_SLOT(EnumSet.of(DAILY, WEEKLY)),
    TOTAL_TIME(EnumSet.noneOf(RankingPeriod.class)),
    TOTAL_DAYS(EnumSet.noneOf(RankingPeriod.class)),
    MAX_STREAK(EnumSet.noneOf(RankingPeriod.class));

    private final Set<RankingPeriod> periods;

    RankingBoardType(Set<RankingPeriod> periods) {
        this.periods = periods;
    }

    /** 명예의 전당 — 리셋 없이 누적되고 진행 중 세션을 반영하지 않는다. */
    public boolean hallOfFame() {
        return periods.isEmpty();
    }

    public boolean supports(RankingPeriod period) {
        return periods.contains(period);
    }
}
