package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static project.study.ranking.RankingBoardType.FOCUS_TIME;
import static project.study.ranking.RankingPeriod.DAILY;
import static project.study.ranking.RankingPeriod.WEEKLY;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import project.study.ranking.dto.RankingBoardResponse;
import project.study.ranking.dto.RankingBoardResponse.BoardEntry;
import project.study.ranking.service.RankingBoardService;

/**
 * 마감된 기간 판은 마감 시각 뒤로 오르지 않고 집중 중 표시도 없다 (BY-828). 진행 중 조각 캐시(10초)가 마감 전 시각의 스냅샷이면
 * 그 조각이 마지막 조각이고 집중 중이라, 지난 기간의 최종 판이 자정 뒤에도 올라가는 일이 있었다.
 */
class RankingClosedBoardTest extends RankingIntegrationTestBase {

    private static final Instant MIDNIGHT = kst(10, 11, 0, 0);

    @Autowired
    private RankingBoardService service;

    private long me;

    /** 10/10 23:00에 시작해 자정을 넘기는 집중 중 draft(23:59:55 수신)와, 순공 3602초로 앞서는 다른 사람 */
    @BeforeEach
    void crossingMidnightDraft() {
        me = user("me");
        session(user("other"), kst(10, 10, 9, 0), 61, 3602);
        Instant lastSeen = kst(10, 10, 23, 59).plusSeconds(55);
        draft(me, kst(10, 10, 23, 0), lastSeen, 3595, "[]");
    }

    private RankingBoardResponse at(Instant now, RankingPeriod period, int offset) {
        clock.set(now);
        return service.board(me, FOCUS_TIME, period, null, offset);
    }

    private void assertSettled(RankingBoardResponse yesterday) {
        assertThat(yesterday.closesAt()).isEqualTo(MIDNIGHT);
        assertThat(yesterday.podium())
                .extracting(BoardEntry::nickname, BoardEntry::value, BoardEntry::focusing)
                .containsExactly(tuple("other", 3602L, false), tuple("me", 3600L, false));
        assertThat(yesterday.around()).noneMatch(BoardEntry::focusing);
        assertThat(yesterday.me().rank()).isEqualTo(2);
        assertThat(yesterday.me().value()).isEqualTo(3600L);
        assertThat(yesterday.me().focusing()).isFalse();
        assertThat(yesterday.above().focusing()).isFalse();
    }

    @Test
    void 캐시된_오늘판이_마감_전_스냅샷이어도_어제판은_자정_값에서_멈춘다() {
        Instant beforeMidnight = MIDNIGHT.minusSeconds(2);
        RankingBoardResponse today = at(beforeMidnight, DAILY, 0);
        assertThat(today.me().value()).isEqualTo(3598L);
        assertThat(today.me().focusing()).isTrue();

        assertSettled(at(MIDNIGHT.plusSeconds(5), DAILY, -1)); // 스냅샷·순위표 캐시(10초) 안
        assertSettled(at(MIDNIGHT.plusSeconds(40), DAILY, -1)); // 캐시가 다시 만들어진 뒤
        assertSettled(at(MIDNIGHT.plusSeconds(50), DAILY, -1));
    }

    @Test
    void 마감_전_스냅샷으로_만든_지난_기간_순위표가_캐시되는_동안에도_더_오르지_않는다() {
        at(MIDNIGHT.minusSeconds(2), WEEKLY, 0); // 다른 판이 마감 전 진행 중 스냅샷을 만든다

        assertSettled(at(MIDNIGHT.plusSeconds(5), DAILY, -1)); // 이 판은 그 스냅샷으로 계산돼 60초 캐시된다
        assertSettled(at(MIDNIGHT.plusSeconds(50), DAILY, -1)); // 스냅샷은 만료됐지만 순위표는 캐시 그대로다
    }
}
