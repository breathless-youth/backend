package project.study.ranking.engine;

import static project.study.studysession.StudySessionThresholds.MIN_LIST_FOCUS_SEC;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingCalendar.Window;
import project.study.ranking.RankingPeriod;
import project.study.studysession.dto.LivePiece;
import project.study.studysession.dto.RankingTotalRow;
import project.study.studysession.entity.SessionSlot;
import project.study.studysession.entity.TimeSlot;
import project.study.studysession.service.RankingSource;

/**
 * 판 하나의 줄 목록을 만든다 (BY-828, ADR-0028). 순공·집중률·시간대는 확정 합계에 진행 중 조각을 더하고, 명예의 전당은 확정
 * 세션만 본다. 결과는 정렬하지 않는다 — Standings.of가 정렬한다.
 *
 * <p>진행 중 조각은 10초 캐시된 스냅샷이라 그 사이 draft가 확정됐을 수 있다. 확정은 세션 저장과 draft 삭제가 한 트랜잭션이므로,
 * 확정 합계를 읽는 것과 같은 REPEATABLE READ 스냅샷에서 남아 있는 draft id를 읽어 이미 확정된 draft의 조각을 뺀다 — 둘 중
 * 한쪽만 보여 이중 집계되는 일이 없다. 그래서 계산 메서드는 프록시를 거쳐(다른 빈에서) 불러야 한다.
 */
@Component
@RequiredArgsConstructor
public class StandingsCalculator {

    /** 집중률 참가 조건 — 주간 순공 10시간, 월간 30시간 (명세 §1-1). */
    static final long WEEKLY_RATE_MIN_FOCUS_SEC = 36_000;

    static final long MONTHLY_RATE_MIN_FOCUS_SEC = 108_000;

    private final RankingSource source;

    /** 판 전체(onlyUserId=null) 또는 한 사용자의 줄. live는 asOf에 나눈 진행 중 조각이다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<RankingEntry> compute(
            RankingBoard board, Window window, Instant asOf, List<LivePiece> live, Long onlyUserId) {
        return switch (board.type()) {
            case FOCUS_TIME, TIME_SLOT ->
                accumulate(board, window, asOf, live, onlyUserId).entrySet().stream()
                        .map(e -> e.getValue().toEntry(e.getKey(), e.getValue().focusSec))
                        .toList();
            case FOCUS_RATE ->
                accumulate(board, window, asOf, live, onlyUserId).entrySet().stream()
                        .filter(e -> e.getValue().focusSec >= requiredRateFocusSec(board.period()))
                        .map(e -> e.getValue().toEntry(e.getKey(), e.getValue().rate()))
                        .toList();
            case TOTAL_TIME ->
                source.periodTotals(RankingCalendar.ALL_TIME_START, window.end(), onlyUserId).stream()
                        .map(r -> RankingEntry.of(r.userId(), r.nickname(), r.focusSec(), r.achievedAt()))
                        .toList();
            case TOTAL_DAYS ->
                source.studyDays(window.end(), onlyUserId).stream()
                        .map(r -> RankingEntry.of(r.userId(), r.nickname(), r.days(), r.achievedAt()))
                        .toList();
            case MAX_STREAK ->
                source.maxStreaks(window.end(), onlyUserId).stream()
                        .map(r -> RankingEntry.of(r.userId(), r.nickname(), r.days(), r.achievedAt()))
                        .toList();
        };
    }

    /** 집중률 판의 한 사용자 합계 — 참가 조건과 상관없이. 기간에 1분 이상 조각이 없으면 empty. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<RateTotals> rateTotals(
            RankingBoard board, Window window, Instant asOf, List<LivePiece> live, long userId) {
        return Optional.ofNullable(accumulate(board, window, asOf, live, userId).get(userId))
                .map(t -> new RateTotals(t.focusSec, t.studySec));
    }

    public static long requiredRateFocusSec(RankingPeriod period) {
        return period == RankingPeriod.MONTHLY ? MONTHLY_RATE_MIN_FOCUS_SEC : WEEKLY_RATE_MIN_FOCUS_SEC;
    }

    private Map<Long, Totals> accumulate(
            RankingBoard board, Window window, Instant asOf, List<LivePiece> live, Long onlyUserId) {
        Map<Long, Totals> totals = new HashMap<>();
        for (RankingTotalRow row : finalizedRows(board, window, onlyUserId)) {
            totals.put(row.userId(), new Totals(row.nickname(), row.focusSec(), row.studySec(), row.achievedAt()));
        }
        for (LivePiece piece : stillOpen(live)) {
            boolean included =
                    (onlyUserId == null || piece.userId() == onlyUserId) && piece.focusSec() >= MIN_LIST_FOCUS_SEC;
            Contribution contribution = included ? contribution(board, window, asOf, piece) : null;
            if (contribution != null) {
                totals.computeIfAbsent(piece.userId(), id -> new Totals(null, 0, 0, Instant.EPOCH))
                        .add(contribution);
            }
        }
        fillLiveOnlyNicknames(totals);
        return totals;
    }

    /**
     * 확정되지 않은 draft가 남아 있는 조각만 — 그 사이 확정·폐기된 draft, 제출이 이미 확정됐는데 뒤늦은 하트비트로 되살아난
     * draft의 조각은 확정 합계가 대신한다. 조각이 없으면 조회하지 않는다.
     */
    private List<LivePiece> stillOpen(List<LivePiece> live) {
        if (live.isEmpty()) {
            return live;
        }
        Set<Long> unfinalizedDraftIds = source.unfinalizedDraftIds();
        return live.stream()
                .filter(piece -> unfinalizedDraftIds.contains(piece.draftId()))
                .toList();
    }

    private List<RankingTotalRow> finalizedRows(RankingBoard board, Window window, Long onlyUserId) {
        return board.type() == RankingBoardType.TIME_SLOT
                ? source.slotTotals(board.slot(), window.start(), window.end(), onlyUserId)
                : source.periodTotals(window.start(), window.end(), onlyUserId);
    }

    /** 조각이 이 판에 더하는 몫 — 판 기간 밖이면 null. 집중 중 표시는 지금 이 판에 값이 오르는 경우에만 켠다. */
    private static Contribution contribution(RankingBoard board, Window window, Instant asOf, LivePiece piece) {
        if (board.type() == RankingBoardType.TIME_SLOT) {
            long focus = piece.slots().stream()
                    .filter(slot -> slot.getSlot() == board.slot() && within(window, slot.getSlotDate()))
                    .mapToLong(SessionSlot::getFocusSec)
                    .sum();
            boolean focusing = piece.latest()
                    && piece.focusing()
                    && TimeSlot.at(asOf) == board.slot()
                    && within(window, TimeSlot.slotDateOf(asOf));
            return focus == 0 ? null : new Contribution(focus, 0, piece.achievedAt(), focusing);
        }
        if (!within(window, piece.statDate())) {
            return null;
        }
        boolean focusing = board.type() == RankingBoardType.FOCUS_TIME && piece.latest() && piece.focusing();
        return new Contribution(piece.focusSec(), piece.studySec(), piece.achievedAt(), focusing);
    }

    /** 진행 중 조각만 있는 사용자는 확정 집계 줄이 없어 닉네임을 따로 읽는다 — 탈퇴자는 여기서 빠진다. */
    private void fillLiveOnlyNicknames(Map<Long, Totals> totals) {
        List<Long> missing = totals.entrySet().stream()
                .filter(e -> e.getValue().nickname == null)
                .map(Map.Entry::getKey)
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        Map<Long, String> nicknames = source.activeNicknames(missing);
        for (Long userId : missing) {
            String nickname = nicknames.get(userId);
            if (nickname == null) {
                totals.remove(userId);
            } else {
                totals.get(userId).nickname = nickname;
            }
        }
    }

    private static boolean within(Window window, LocalDate date) {
        return !date.isBefore(window.start()) && !date.isAfter(window.end());
    }

    private record Contribution(long focusSec, long studySec, Instant achievedAt, boolean focusing) {}

    private static final class Totals {

        private String nickname;
        private long focusSec;
        private long studySec;
        private Instant achievedAt;
        private boolean focusing;

        private Totals(String nickname, long focusSec, long studySec, Instant achievedAt) {
            this.nickname = nickname;
            this.focusSec = focusSec;
            this.studySec = studySec;
            this.achievedAt = achievedAt;
        }

        private void add(Contribution contribution) {
            focusSec += contribution.focusSec();
            studySec += contribution.studySec();
            if (contribution.achievedAt().isAfter(achievedAt)) {
                achievedAt = contribution.achievedAt();
            }
            focusing |= contribution.focusing();
        }

        private double rate() {
            return studySec == 0 ? 0 : focusSec * 100.0 / studySec;
        }

        private RankingEntry toEntry(long userId, double value) {
            return new RankingEntry(userId, nickname, value, achievedAt, focusing, focusSec, studySec);
        }
    }
}
