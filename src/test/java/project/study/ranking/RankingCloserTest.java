package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import project.study.ranking.close.RankingCloser;

/** 마감 1분 뒤 직전 기간을 확정하고, 1시간이 넘으면 건너뛴다 (BY-828, ADR-0029). 기준 시각은 closeDue 인자다. */
class RankingCloserTest extends RankingIntegrationTestBase {

    private static final LocalDate FRI = LocalDate.of(2026, 10, 9);

    @Autowired
    private RankingCloser closer;

    /** "판 닉네임 순위 값" — 판·순위 순. */
    private List<String> records() {
        return jdbc.queryForList("""
                SELECT r.board_key || ' ' || u.nickname || ' ' || r.rank || ' ' || r.value
                FROM ranking_record r JOIN users u ON u.id = r.user_id
                ORDER BY r.board_key, r.rank""", String.class);
    }

    /** 마감 표시의 skipped — 표시가 없으면 null. */
    private Boolean skipped(String boardKey, LocalDate periodStart) {
        return jdbc.query(
                "SELECT skipped FROM ranking_close WHERE board_key = ? AND period_start = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null,
                boardKey,
                periodStart);
    }

    @Test
    void 마감_1분_뒤_일간판을_확정하고_30분_이상인_1_3위만_메달을_받는다() {
        long a = user("a");
        long b = user("b");
        long c = user("c");
        session(a, kst(10, 9, 9, 0), 60, 3000); // 오전
        session(c, kst(10, 9, 13, 0), 60, 2400); // 오후
        session(b, kst(10, 9, 14, 0), 30, 1700); // 오후, 30분 미만 — 3위여도 메달 없음

        closer.closeDue(kst(10, 10, 0, 1));

        assertThat(records())
                .containsExactly(
                        "FOCUS_TIME:DAILY a 1 3000.0",
                        "FOCUS_TIME:DAILY c 2 2400.0",
                        "TIME_SLOT:DAILY:AFTERNOON c 1 2400.0",
                        "TIME_SLOT:DAILY:MORNING a 1 3000.0");
        assertThat(skipped("FOCUS_TIME:DAILY", FRI)).isFalse();
    }

    @Test
    void 마감_1분이_지나기_전에는_확정하지_않는다() {
        session(user("a"), kst(10, 9, 9, 0), 60, 3000);

        closer.closeDue(kst(10, 10, 0, 0).plusSeconds(59));

        assertThat(skipped("FOCUS_TIME:DAILY", FRI)).isNull();
        assertThat(records()).isEmpty();
    }

    @Test
    void 다시_돌려도_확정된_기록은_늦은_제출로_바뀌지_않는다() {
        session(user("a"), kst(10, 9, 9, 0), 60, 3000);
        closer.closeDue(kst(10, 10, 0, 1));
        session(user("b"), kst(10, 9, 10, 0), 90, 5000); // 마감 뒤에 들어온 늦은 제출

        closer.closeDue(kst(10, 10, 0, 2));

        assertThat(records()).containsExactly("FOCUS_TIME:DAILY a 1 3000.0", "TIME_SLOT:DAILY:MORNING a 1 3000.0");
    }

    @Test
    void 마감_1시간이_지나면_기록하지_않고_건너뛴_것으로_표시한다() {
        session(user("a"), kst(10, 9, 9, 0), 60, 3000);

        closer.closeDue(kst(10, 10, 1, 1));

        assertThat(skipped("FOCUS_TIME:DAILY", FRI)).isTrue();
        assertThat(records()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ranking_best", Integer.class))
                .isZero();
    }

    @Test
    void 진행_중_draft는_보정_없이_보고된_값을_마감_시각까지만_더한다() {
        long a = user("a");
        // 앱 시계가 서버보다 70초 늦다 — 보고 시각은 자정 전, 서버 수신은 자정 뒤라 마감 계산 시각(00:01)에도 집중 중이다.
        // 보정하면 보고 뒤 20초가 금요일에 더해져 3590이 된다
        activeStudySessionRepository.upsertSnapshot(
                a,
                kst(10, 9, 23, 0),
                kst(10, 9, 23, 59).plusSeconds(30),
                kst(10, 10, 0, 0).plusSeconds(40),
                3570,
                3570,
                "[]");

        closer.closeDue(kst(10, 10, 0, 1));

        assertThat(records()).containsExactly("FOCUS_TIME:DAILY a 1 3570.0");
    }

    @Test
    void 탈퇴한_사용자는_마감에서_빠지고_다음_사람이_1위다() {
        long a = user("a");
        long b = user("b");
        session(a, kst(10, 9, 9, 0), 60, 3500);
        session(b, kst(10, 9, 13, 0), 60, 3000);
        withdraw(a);

        closer.closeDue(kst(10, 10, 0, 1));

        assertThat(records()).containsExactly("FOCUS_TIME:DAILY b 1 3000.0", "TIME_SLOT:DAILY:AFTERNOON b 1 3000.0");
    }

    @Test
    void 집중률_주간판은_30분_조건_없이_소수_1자리로_기록한다() {
        session(user("a"), kst(9, 29, 8, 0), 660, 36_000); // 11시간 중 순공 10시간 — 90.909%

        closer.closeDue(kst(10, 5, 0, 1));

        assertThat(records()).contains("FOCUS_RATE:WEEKLY a 1 90.9", "FOCUS_TIME:WEEKLY a 1 36000.0");
    }
}
