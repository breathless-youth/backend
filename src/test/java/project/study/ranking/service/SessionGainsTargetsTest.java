package project.study.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.ranking.RankingBoardType.FOCUS_RATE;
import static project.study.ranking.RankingBoardType.FOCUS_TIME;
import static project.study.ranking.RankingBoardType.MAX_STREAK;
import static project.study.ranking.RankingBoardType.TIME_SLOT;
import static project.study.ranking.RankingBoardType.TOTAL_DAYS;
import static project.study.ranking.RankingBoardType.TOTAL_TIME;
import static project.study.ranking.RankingPeriod.DAILY;
import static project.study.ranking.RankingPeriod.MONTHLY;
import static project.study.ranking.RankingPeriod.WEEKLY;
import static project.study.studysession.entity.TimeSlot.MORNING;
import static project.study.studysession.entity.TimeSlot.NIGHT;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import project.study.ranking.RankingBoard;

/** 세션 뒤 오른 랭킹이 훑는 판의 순서 — 명세 칩 순서를 고정한다 (BY-828 §7.3). */
class SessionGainsTargetsTest {

    @Test
    void 판은_명세_칩_순서로_이번_제출이_지난_시간대만_끼워_돌려준다() {
        assertThat(RankingSessionGainsService.targets(EnumSet.of(MORNING, NIGHT)))
                .containsExactly(
                        new RankingBoard(FOCUS_TIME, DAILY, null),
                        new RankingBoard(FOCUS_TIME, WEEKLY, null),
                        new RankingBoard(FOCUS_TIME, MONTHLY, null),
                        new RankingBoard(FOCUS_RATE, WEEKLY, null),
                        new RankingBoard(FOCUS_RATE, MONTHLY, null),
                        new RankingBoard(TIME_SLOT, DAILY, MORNING),
                        new RankingBoard(TIME_SLOT, DAILY, NIGHT),
                        new RankingBoard(TIME_SLOT, WEEKLY, MORNING),
                        new RankingBoard(TIME_SLOT, WEEKLY, NIGHT),
                        new RankingBoard(TOTAL_TIME, null, null),
                        new RankingBoard(TOTAL_DAYS, null, null),
                        new RankingBoard(MAX_STREAK, null, null));
    }
}
