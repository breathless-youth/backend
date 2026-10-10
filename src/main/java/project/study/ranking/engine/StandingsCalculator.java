package project.study.ranking.engine;

import static project.study.studysession.StudySessionThresholds.MIN_LIST_FOCUS_SEC;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingCalendar.Window;
import project.study.ranking.RankingPeriod;
import project.study.studysession.dto.LivePiece;
import project.study.studysession.dto.RankingPastRow;
import project.study.studysession.dto.RankingTotalRow;
import project.study.studysession.entity.SessionSlot;
import project.study.studysession.entity.TimeSlot;
import project.study.studysession.service.RankingSource;

/**
 * 판 하나의 줄 목록을 만든다 (BY-828, ADR-0028). 순공·집중률·시간대는 확정 합계에 진행 중 조각을 더하고, 명예의 전당은 확정
 * 세션만 본다. 결과는 정렬하지 않는다 — Standings.of가 정렬한다.
 *
 * <p>진행 중 조각은 10초 캐시된 스냅샷이라 그 사이 draft가 확정됐을 수 있다. 확정은 세션 저장과 draft 삭제가 한 트랜잭션이므로,
 * 확정 합계를 읽는 것과 같은 REPEATABLE READ 스냅샷에서 확정되지 않은 draft id를 읽어 draft가 이미 사라졌거나 제출이 이미
 * 확정된 draft의 조각을 뺀다 — 둘 중 한쪽만 보여 이중 집계되는 일이 없다. 그래서 계산 메서드는 프록시를 거쳐(다른 빈에서)
 * 불러야 한다.
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
        return compute(board, window, asOf, live, onlyUserId, null);
    }

    /**
     * excludeSubmission을 주면 그 제출(submission_started_at)의 확정 조각을 빼고 계산한다 — 세션 뒤 오른 랭킹의 "이번 세션 전" 내
     * 값(BY-828 §7.3). 진행 중 조각은 그대로 둔다. 한 사용자(onlyUserId)를 계산할 때 쓴다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<RankingEntry> compute(
            RankingBoard board,
            Window window,
            Instant asOf,
            List<LivePiece> live,
            Long onlyUserId,
            Instant excludeSubmission) {
        requireRepeatableRead();
        if (excludeSubmission != null && onlyUserId == null) {
            throw new IllegalArgumentException("제출 제외는 한 사용자 계산에서만 쓴다");
        }
        return switch (board.type()) {
            case FOCUS_TIME, TIME_SLOT ->
                accumulate(board, window, asOf, live, onlyUserId, excludeSubmission).entrySet().stream()
                        .map(e -> e.getValue().toEntry(e.getKey(), e.getValue().focusSec))
                        .toList();
            case FOCUS_RATE ->
                accumulate(board, window, asOf, live, onlyUserId, excludeSubmission).entrySet().stream()
                        .filter(e -> e.getValue().focusSec >= requiredRateFocusSec(board.period()))
                        .map(e -> e.getValue().toEntry(e.getKey(), e.getValue().rate()))
                        .toList();
            case TOTAL_TIME ->
                source
                        .periodTotals(RankingCalendar.ALL_TIME_START, window.end(), onlyUserId, excludeSubmission)
                        .stream()
                        .map(r -> RankingEntry.of(r.userId(), r.nickname(), r.focusSec(), r.achievedAt()))
                        .toList();
            case TOTAL_DAYS ->
                source.studyDays(window.end(), onlyUserId, excludeSubmission).stream()
                        .map(r -> RankingEntry.of(r.userId(), r.nickname(), r.days(), r.achievedAt()))
                        .toList();
            case MAX_STREAK ->
                source.maxStreaks(window.end(), onlyUserId, excludeSubmission).stream()
                        .map(r -> RankingEntry.of(r.userId(), r.nickname(), r.days(), r.achievedAt()))
                        .toList();
        };
    }

    /** 집중률 판의 한 사용자 합계 — 참가 조건과 상관없이. 기간에 1분 이상 조각이 없으면 empty. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<RateTotals> rateTotals(
            RankingBoard board, Window window, Instant asOf, List<LivePiece> live, long userId) {
        requireRepeatableRead();
        return Optional.ofNullable(
                        accumulate(board, window, asOf, live, userId, null).get(userId))
                .map(t -> new RateTotals(t.focusSec, t.studySec));
    }

    /**
     * 기간 판의 since 시점 값 (BY-828 §7.3, 첫 접속 추월) — 확정 조각은 SQL로, 진행 중 조각은 같은 규칙(끝난 조각 전부, 걸친 조각은
     * 시간 비율)으로 되돌린다. compute와 같은 스냅샷 규칙으로 아직 확정되지 않은 draft의 조각만 더한다. 값이 0인 사람(since 뒤에만
     * 공부)도 돌려준다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<PastEntry> pastTotals(Window window, Instant since, List<LivePiece> live) {
        requireRepeatableRead();
        Map<Long, PastEntry> byUser = new HashMap<>();
        for (RankingPastRow row : source.periodTotalsAt(window.start(), window.end(), since)) {
            byUser.put(
                    row.userId(),
                    new PastEntry(row.userId(), row.nickname(), row.focusSec(), row.achievedAt(), row.studiedFrom()));
        }
        for (LivePiece piece : stillOpen(live)) {
            if (piece.focusSec() >= MIN_LIST_FOCUS_SEC && within(window, piece.statDate())) {
                byUser.merge(piece.userId(), PastEntry.of(piece, since), PastEntry::plus);
            }
        }
        return withNicknames(byUser);
    }

    /**
     * 바깥 트랜잭션에 합류하면 그쪽 격리 수준이 이겨서 한 스냅샷 보장이 조용히 사라진다 — 기본 격리 수준의 바깥 트랜잭션이거나
     * 트랜잭션 밖에서 불렀으면 바로 실패시킨다.
     */
    private static void requireRepeatableRead() {
        Integer level = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (level == null || level != Connection.TRANSACTION_REPEATABLE_READ) {
            throw new IllegalStateException("랭킹 집계는 자체 REPEATABLE_READ 트랜잭션에서만 실행한다(현재 격리 수준: " + level
                    + "). 바깥 트랜잭션 안에서 부르면 확정 합계와 draft id를 한 스냅샷에서 읽는 보장이 사라진다");
        }
    }

    public static long requiredRateFocusSec(RankingPeriod period) {
        return period == RankingPeriod.MONTHLY ? MONTHLY_RATE_MIN_FOCUS_SEC : WEEKLY_RATE_MIN_FOCUS_SEC;
    }

    private Map<Long, Totals> accumulate(
            RankingBoard board,
            Window window,
            Instant asOf,
            List<LivePiece> live,
            Long onlyUserId,
            Instant excludeSubmission) {
        Map<Long, Totals> totals = new HashMap<>();
        for (RankingTotalRow row : finalizedRows(board, window, onlyUserId, excludeSubmission)) {
            totals.put(row.userId(), new Totals(row.nickname(), row.focusSec(), row.studySec(), row.achievedAt()));
        }
        for (LivePiece piece : stillOpen(piecesOf(live, onlyUserId))) {
            Contribution contribution =
                    piece.focusSec() >= MIN_LIST_FOCUS_SEC ? contribution(board, window, asOf, piece) : null;
            if (contribution != null) {
                totals.computeIfAbsent(piece.userId(), id -> new Totals(null, 0, 0, Instant.EPOCH))
                        .add(contribution);
            }
        }
        fillLiveOnlyNicknames(totals);
        return totals;
    }

    /** 한 사용자만 계산할 때는 그 사용자의 조각만 남겨, 남은 draft id 조회가 다른 사람의 조각 때문에 돌지 않게 한다. */
    private static List<LivePiece> piecesOf(List<LivePiece> live, Long onlyUserId) {
        return onlyUserId == null
                ? live
                : live.stream().filter(piece -> piece.userId() == onlyUserId).toList();
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

    private List<RankingTotalRow> finalizedRows(
            RankingBoard board, Window window, Long onlyUserId, Instant excludeSubmission) {
        return board.type() == RankingBoardType.TIME_SLOT
                ? source.slotTotals(board.slot(), window.start(), window.end(), onlyUserId, excludeSubmission)
                : source.periodTotals(window.start(), window.end(), onlyUserId, excludeSubmission);
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

    /** 진행 중 조각만 있는 사람의 닉네임을 채운다 — 탈퇴자와 닉네임 없는 사람은 빠진다. */
    private List<PastEntry> withNicknames(Map<Long, PastEntry> byUser) {
        List<Long> missing = byUser.values().stream()
                .filter(entry -> entry.nickname() == null)
                .map(PastEntry::userId)
                .toList();
        Map<Long, String> nicknames = missing.isEmpty() ? Map.of() : source.activeNicknames(missing);
        List<PastEntry> entries = new ArrayList<>(byUser.size());
        for (PastEntry entry : byUser.values()) {
            if (entry.nickname() != null) {
                entries.add(entry);
            } else if (nicknames.containsKey(entry.userId())) {
                entries.add(entry.withNickname(nicknames.get(entry.userId())));
            }
        }
        return entries;
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
