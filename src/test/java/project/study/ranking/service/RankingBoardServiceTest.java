package project.study.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static project.study.ranking.RankingBoardType.FOCUS_TIME;
import static project.study.ranking.RankingPeriod.DAILY;
import static project.study.ranking.RankingPeriod.WEEKLY;

import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import project.study.ranking.RankingIntegrationTestBase;
import project.study.ranking.dto.RankingBoardResponse;
import project.study.ranking.dto.RankingBoardResponse.BoardEntry;
import project.study.ranking.dto.RankingBoardResponse.BoardMe;
import project.study.ranking.dto.RankingBoardResponse.BoardNeighbor;
import project.study.ranking.dto.RankingBoardResponse.GoalExample;

/** 순공 판으로 공통 응답(시상대·내 순위·앞뒤·실시간·직전 기간)을 확인한다 (BY-828). */
class RankingBoardServiceTest extends RankingIntegrationTestBase {

    @Autowired
    private RankingBoardService service;

    /** 이번 주 화요일(10/6) 09:00에 분 단위 순공 세션 하나 */
    private long weekly(String nickname, int focusMinutes) {
        long id = user(nickname);
        session(id, kst(10, 6, 9, 0), focusMinutes, focusMinutes * 60);
        return id;
    }

    private RankingBoardResponse weeklyBoard(long userId) {
        return service.board(userId, FOCUS_TIME, WEEKLY, null, 0);
    }

    @Test
    void 순공_주간은_시상대_내_순위_앞뒤_두_명을_준다() {
        long[] ids = new long[7];
        for (int i = 0; i < 7; i++) {
            ids[i] = weekly("u" + (i + 1), 70 - i * 10);
        }

        RankingBoardResponse r = weeklyBoard(ids[3]);

        assertThat(r.periodStart()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(r.closesAt()).isEqualTo(kst(10, 12, 0, 0));
        assertThat(r.asOf()).isEqualTo(NOW);
        assertThat(r.podium()).extracting(BoardEntry::nickname).containsExactly("u1", "u2", "u3");
        assertThat(r.me()).isEqualTo(new BoardMe(4, 2400L, false, 58));
        assertThat(r.around()).extracting(BoardEntry::rank).containsExactly(2, 3, 4, 5, 6);
        assertThat(r.around())
                .filteredOn(BoardEntry::me)
                .extracting(BoardEntry::nickname)
                .containsExactly("u4");
        assertThat(r.above()).isEqualTo(new BoardNeighbor("u3", 600L, false, null));
        assertThat(r.below()).isEqualTo(new BoardNeighbor("u5", 600L, false, null));
        assertThat(r.startNowRank()).isNull();
        assertThat(r.goalExamples()).isNull();
    }

    @Test
    void 일위면_뒤로_네_명이고_바로_위가_없다() {
        long first = weekly("u1", 70);
        for (int i = 2; i <= 6; i++) {
            weekly("u" + i, 70 - (i - 1) * 10);
        }

        RankingBoardResponse r = weeklyBoard(first);

        assertThat(r.around()).extracting(BoardEntry::rank).containsExactly(1, 2, 3, 4, 5);
        assertThat(r.above()).isNull();
        assertThat(r.below()).isEqualTo(new BoardNeighbor("u2", 600L, false, null));
    }

    @Test
    void 이번_주_기록이_없으면_지금_시작할_때_순위와_지난주_분포로_만든_목표를_준다() {
        for (int i = 1; i <= 10; i++) {
            session(user("last" + i), kst(10, 1, 9, 0), i * 10, i * 600);
        }
        for (int i = 1; i <= 3; i++) {
            weekly("now" + i, 30);
        }
        long me = user("me");

        RankingBoardResponse r = weeklyBoard(me);

        assertThat(r.me()).isNull();
        assertThat(r.around()).isEmpty();
        assertThat(r.startNowRank()).isEqualTo(4);
        assertThat(r.goalExamples())
                .containsExactly(
                        new GoalExample(10, 6060),
                        new GoalExample(20, 5460),
                        new GoalExample(30, 4860),
                        new GoalExample(50, 3660));
    }

    @Test
    void 집중_중인_사람은_요청_시각까지_값이_오르고_추월하면_순위가_바뀐다() {
        long me = weekly("me", 60);
        session(user("b"), kst(10, 6, 12, 0), 11, 610);
        draft(user("a"), NOW.minusSeconds(600), NOW.minusSeconds(10), 590, "[]");

        assertThat(weeklyBoard(me).podium())
                .extracting(BoardEntry::nickname, BoardEntry::value, BoardEntry::focusing)
                .containsExactly(tuple("me", 3600L, false), tuple("b", 610L, false), tuple("a", 600L, true));

        clock.advance(Duration.ofSeconds(5)); // 캐시(10초) 안 — 캐시된 값을 요청 시각으로 올린다
        RankingBoardResponse later = weeklyBoard(me);
        assertThat(later.asOf()).isEqualTo(NOW.plusSeconds(5));
        assertThat(later.podium().get(2).value()).isEqualTo(605L);

        clock.advance(Duration.ofSeconds(15)); // 마지막 수신 뒤 30초 — 620초로 b(610)를 넘는다
        assertThat(weeklyBoard(me).podium()).extracting(BoardEntry::nickname).containsExactly("me", "a", "b");
    }

    @Test
    void 세션을_막_끝낸_내_값은_캐시와_상관없이_바로_반영되고_내가_두_번_나오지_않는다() {
        weekly("other", 50);
        long me = weekly("me", 40);
        assertThat(weeklyBoard(me).me().rank()).isEqualTo(2);

        session(me, kst(10, 10, 14, 0), 20, 1200);
        RankingBoardResponse r = weeklyBoard(me);

        assertThat(r.me()).isEqualTo(new BoardMe(1, 3600L, false, 50));
        assertThat(r.podium()).extracting(BoardEntry::nickname).containsExactly("me", "other");
    }

    @Test
    void 직전_기간을_조회할_수_있다() {
        long me = user("me");
        session(me, kst(10, 9, 9, 0), 30, 1800);

        RankingBoardResponse r = service.board(me, FOCUS_TIME, DAILY, null, -1);

        assertThat(r.periodStart()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(r.closesAt()).isEqualTo(kst(10, 10, 0, 0));
        assertThat(r.me().value()).isEqualTo(1800L);
    }

    @Test
    void 지난_기간에는_지금_시작하면_받을_순위를_주지_않는다() {
        session(user("yesterday"), kst(10, 9, 9, 0), 30, 1800);
        session(user("today"), kst(10, 10, 9, 0), 30, 1800);
        long me = user("me");

        assertThat(service.board(me, FOCUS_TIME, DAILY, null, 0).startNowRank()).isEqualTo(2);
        RankingBoardResponse closed = service.board(me, FOCUS_TIME, DAILY, null, -1);
        assertThat(closed.me()).isNull();
        assertThat(closed.startNowRank()).isNull();
    }
}
