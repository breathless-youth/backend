package project.study.ranking.engine;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;

/**
 * 순위표 한 줄 (BY-828). value는 정렬 값 — 시간 판은 초, 일수 판은 일, 집중률은 % 원값. focusSec·studySec는 집중률·순공 판에서만
 * 채운다(따라잡기 계산용). nickname은 가정한 줄(예상 순위 계산)이면 null일 수 있다.
 */
public record RankingEntry(
        long userId,
        String nickname,
        double value,
        Instant achievedAt,
        boolean focusing,
        long focusSec,
        long studySec) {

    /** 값 내림차순 → 먼저 도달한 사람 → userId (공동 순위 없음). */
    public static final Comparator<RankingEntry> ORDER = Comparator.comparingDouble(RankingEntry::value)
            .reversed()
            .thenComparing(RankingEntry::achievedAt)
            .thenComparingLong(RankingEntry::userId);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static RankingEntry of(long userId, String nickname, double value, Instant achievedAt) {
        return new RankingEntry(userId, nickname, value, achievedAt, false, 0, 0);
    }

    /** 집중 중이면 from부터 to까지 흐른 초만큼 값을 올린다 — 캐시된 값을 요청 시각으로 맞춘다. */
    public RankingEntry advancedTo(Instant from, Instant to) {
        long seconds = Duration.between(from, to).toSeconds();
        if (!focusing || seconds <= 0) {
            return this;
        }
        return new RankingEntry(userId, nickname, value + seconds, to, true, focusSec + seconds, studySec + seconds);
    }

    /**
     * 마감된 판의 줄 — 집중 중이면 마감 시각까지만 올리고 집중 중 표시를 끈다. 기준 시각이 이미 마감 뒤면 값은 그대로 둔다(뒤로
     * 돌리지 않는다).
     */
    public RankingEntry settledAt(Instant from, Instant closesAt) {
        RankingEntry advanced = advancedTo(from, closesAt);
        if (!advanced.focusing) {
            return advanced;
        }
        return new RankingEntry(
                userId, nickname, advanced.value, advanced.achievedAt, false, advanced.focusSec, advanced.studySec);
    }

    /** 도달 시각의 KST 날짜 — 조각 종료는 반개구간의 끝이라 자정에 끝난 조각은 그 전날의 기록이다. */
    public LocalDate achievedDate() {
        return achievedAt.minusNanos(1).atZone(KST).toLocalDate();
    }
}
