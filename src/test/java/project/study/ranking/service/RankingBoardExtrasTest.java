package project.study.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.ranking.RankingBoardType.FOCUS_RATE;
import static project.study.ranking.RankingBoardType.MAX_STREAK;
import static project.study.ranking.RankingBoardType.TIME_SLOT;
import static project.study.ranking.RankingBoardType.TOTAL_TIME;
import static project.study.ranking.RankingPeriod.DAILY;
import static project.study.ranking.RankingPeriod.WEEKLY;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import project.study.ranking.RankingIntegrationTestBase;
import project.study.ranking.dto.RankingBoardResponse;
import project.study.ranking.dto.RankingBoardResponse.BoardEntry;
import project.study.ranking.dto.RankingBoardResponse.NextTier;
import project.study.ranking.dto.RankingBoardResponse.RateEligibility;
import project.study.ranking.dto.RankingBoardResponse.StreakCard;
import project.study.ranking.dto.RankingBoardResponse.StreakGroupRow;
import project.study.studysession.entity.TimeSlot;

/** 판별 추가 필드 — 집중률·시간대·누적 시간·연속 공부 일수 (BY-828). */
class RankingBoardExtrasTest extends RankingIntegrationTestBase {

    @Autowired
    private RankingBoardService service;

    private void streak(long userId, LocalDate first, int days) {
        for (int i = 0; i < days; i++) {
            session(userId, first.plusDays(i).atTime(9, 0).atZone(KST).toInstant(), 15, 900);
        }
    }

    @Test
    void 집중률_참가_조건_미달이면_진행도와_예상_순위와_그_자리_주변을_준다() {
        session(user("x"), kst(10, 6, 0, 30), 720, 39_600);
        session(user("y"), kst(10, 7, 0, 30), 720, 43_200);
        long me = user("me");
        session(me, kst(10, 8, 0, 30), 600, 32_400);

        RankingBoardResponse r = service.board(me, FOCUS_RATE, WEEKLY, null, 0);

        assertThat(r.me()).isNull();
        assertThat(r.eligibility()).isEqualTo(new RateEligibility(false, 32_400, 36_000, 90.0, 3));
        assertThat(r.around()).extracting(BoardEntry::nickname).containsExactly("y", "x");
        assertThat(r.startNowRank()).isNull();
    }

    @Test
    void 집중률은_바로_위를_넘는_데_필요한_순공을_준다() {
        session(user("x"), kst(10, 6, 0, 30), 720, 39_600);
        long me = user("me");
        session(me, kst(10, 8, 0, 30), 720, 38_000);

        RankingBoardResponse r = service.board(me, FOCUS_RATE, WEEKLY, null, 0);

        assertThat(r.me().rank()).isEqualTo(2);
        assertThat(r.above().catchUpFocusSec()).isEqualTo(1601L);
        assertThat(r.above().gap()).isEqualTo(3.7);
        assertThat(r.eligibility().eligible()).isTrue();
    }

    @Test
    void 집중률_차이는_화면에_보이는_두_값의_차이다() {
        session(user("x"), kst(10, 6, 0, 30), 720, 39_597); // 91.66 -> 91.7
        long me = user("me");
        session(me, kst(10, 8, 0, 30), 720, 37_990); // 87.94 -> 87.9

        RankingBoardResponse r = service.board(me, FOCUS_RATE, WEEKLY, null, 0);

        assertThat(r.above().gap()).isEqualTo(3.8);
        assertThat(r.me().value()).isEqualTo(87.9);
    }

    @Test
    void 시간대_구간을_안_주면_지금_구간이다() {
        long me = user("me");
        session(me, kst(10, 10, 12, 30), 60, 3600);

        RankingBoardResponse r = service.board(me, TIME_SLOT, DAILY, null, 0);

        assertThat(r.slot()).isEqualTo(TimeSlot.AFTERNOON);
        assertThat(r.me().value()).isEqualTo(3600L);
        assertThat(r.closesAt()).isEqualTo(kst(10, 11, 0, 0));
    }

    @Test
    void 누적_시간은_다음_구간까지_남은_양과_지금_페이스로_걸리는_날을_준다() {
        long me = 0;
        for (int hours = 1; hours <= 20; hours++) {
            long id = user("u" + hours);
            session(id, kst(10, 9, 0, 0), hours * 60, hours * 3600);
            if (hours == 18) {
                me = id;
            }
        }

        RankingBoardResponse r = service.board(me, TOTAL_TIME, null, null, 0);

        assertThat(r.me().rank()).isEqualTo(3);
        assertThat(r.me().topPercent()).isEqualTo(15);
        assertThat(r.nextTier()).isEqualTo(new NextTier(10, 3601, 1));
        assertThat(r.periodStart()).isNull();
        assertThat(r.closesAt()).isNull();
    }

    @Test
    void 연속_공부_일수는_같은_일수끼리_묶고_내_묶음엔_몇_번째로_달성했는지_준다() {
        streak(user("a"), LocalDate.of(2026, 9, 1), 5);
        streak(user("b"), LocalDate.of(2026, 9, 2), 5);
        streak(user("c"), LocalDate.of(2026, 9, 10), 4);
        long me = user("me");
        streak(me, LocalDate.of(2026, 9, 20), 4);
        streak(user("d"), LocalDate.of(2026, 9, 25), 3);

        RankingBoardResponse r = service.board(me, MAX_STREAK, null, null, 0);

        assertThat(r.me().rank()).isEqualTo(4);
        assertThat(r.around()).isEmpty();
        assertThat(r.aroundGroups())
                .containsExactly(
                        new StreakGroupRow(5, 1, "a", 1, LocalDate.of(2026, 9, 5), false, null),
                        new StreakGroupRow(4, 4, "me", 1, LocalDate.of(2026, 9, 23), true, 2),
                        new StreakGroupRow(3, 5, "d", 0, LocalDate.of(2026, 9, 27), false, null));
        assertThat(r.streak())
                .isEqualTo(new StreakCard(4, LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 23), 0, false, null));
    }

    @Test
    void 지금_연속이_최고_기록이면_내일도_하면_받을_순위를_준다() {
        streak(user("p"), LocalDate.of(2026, 9, 1), 5);
        streak(user("q"), LocalDate.of(2026, 9, 10), 4);
        long me = user("me");
        streak(me, LocalDate.of(2026, 10, 7), 3); // 10/7~10/9, 오늘(10/10)은 아직

        RankingBoardResponse r = service.board(me, MAX_STREAK, null, null, 0);

        assertThat(r.streak())
                .isEqualTo(new StreakCard(3, LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 9), 3, true, 3));
    }
}
