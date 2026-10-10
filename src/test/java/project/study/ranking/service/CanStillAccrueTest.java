package project.study.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingPeriod;
import project.study.studysession.entity.TimeSlot;

/** 시간대 판에 지금부터 마감 전까지 그 구간이 남아 있는가 (BY-828 §7.2). 2026-10-10(토) 15:00 KST 기준. */
class CanStillAccrueTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Instant SAT_AFTERNOON =
            ZonedDateTime.of(2026, 10, 10, 15, 0, 0, 0, KST).toInstant();
    private static final Instant SUN_AFTERNOON =
            ZonedDateTime.of(2026, 10, 11, 15, 0, 0, 0, KST).toInstant();

    private static boolean canAccrue(RankingBoard board, Instant now) {
        return RankingRecordSummaryService.canStillAccrue(board, RankingCalendar.window(board, now, 0), now);
    }

    private static RankingBoard slot(RankingPeriod period, TimeSlot slot) {
        return new RankingBoard(RankingBoardType.TIME_SLOT, period, slot);
    }

    @Test
    void 오늘_이미_지난_구간의_일간판은_더_쌓을_수_없다() {
        assertThat(canAccrue(slot(RankingPeriod.DAILY, TimeSlot.MORNING), SAT_AFTERNOON))
                .isFalse();
    }

    @Test
    void 오늘_아직_안_온_구간의_일간판은_쌓을_수_있다() {
        assertThat(canAccrue(slot(RankingPeriod.DAILY, TimeSlot.EVENING), SAT_AFTERNOON))
                .isTrue();
    }

    @Test
    void 심야_일간판은_마감이_다음날_04시라_저녁에도_쌓을_수_있다() {
        assertThat(canAccrue(slot(RankingPeriod.DAILY, TimeSlot.NIGHT), SAT_AFTERNOON))
                .isTrue();
    }

    @Test
    void 주간판은_이번_주가_남아_있으면_오늘_지난_구간도_쌓을_수_있다() {
        assertThat(canAccrue(slot(RankingPeriod.WEEKLY, TimeSlot.MORNING), SAT_AFTERNOON))
                .isTrue();
    }

    @Test
    void 주간판도_일요일_오후엔_그날_지난_구간을_더_쌓을_수_없다() {
        assertThat(canAccrue(slot(RankingPeriod.WEEKLY, TimeSlot.MORNING), SUN_AFTERNOON))
                .isFalse();
    }

    @Test
    void 순공_판은_늘_쌓을_수_있다() {
        assertThat(canAccrue(new RankingBoard(RankingBoardType.FOCUS_TIME, RankingPeriod.DAILY, null), SAT_AFTERNOON))
                .isTrue();
    }
}
