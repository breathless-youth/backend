package project.study.ranking;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import project.study.studysession.entity.TimeSlot;

/** 랭킹판의 기간 범위와 마감 시각 (BY-828). 모든 경계는 KST다. */
public final class RankingCalendar {

    /** 명예의 전당 집계 시작일 — 서비스 시작 전이라 모든 기록을 포함한다. */
    public static final LocalDate ALL_TIME_START = LocalDate.of(2000, 1, 1);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime NIGHT_CLOSE = LocalTime.of(4, 0);

    private RankingCalendar() {}

    /** 날짜 범위 [start, end]와 마감 시각. 명예의 전당은 처음부터 오늘까지이고 마감이 없다(null). */
    public record Window(LocalDate start, LocalDate end, Instant closesAt) {}

    /** offset -1은 직전 기간. 심야판은 (지금 − 4시간)의 날짜가 기준이고 마감이 4시간 늦다. */
    public static Window window(RankingBoard board, Instant now, int offset) {
        if (board.type().hallOfFame()) {
            return new Window(ALL_TIME_START, now.atZone(KST).toLocalDate(), null);
        }
        boolean night = board.slot() == TimeSlot.NIGHT;
        LocalDate reference = night ? TimeSlot.slotDateOf(now) : now.atZone(KST).toLocalDate();
        LocalDate start = shift(periodStart(board.period(), reference), board.period(), offset);
        LocalDate next = shift(start, board.period(), 1);
        LocalTime closeTime = night ? NIGHT_CLOSE : LocalTime.MIDNIGHT;
        return new Window(
                start, next.minusDays(1), next.atTime(closeTime).atZone(KST).toInstant());
    }

    private static LocalDate periodStart(RankingPeriod period, LocalDate date) {
        return switch (period) {
            case DAILY -> date;
            case WEEKLY -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTHLY -> date.withDayOfMonth(1);
        };
    }

    private static LocalDate shift(LocalDate start, RankingPeriod period, int periods) {
        return switch (period) {
            case DAILY -> start.plusDays(periods);
            case WEEKLY -> start.plusWeeks(periods);
            case MONTHLY -> start.plusMonths(periods);
        };
    }
}
