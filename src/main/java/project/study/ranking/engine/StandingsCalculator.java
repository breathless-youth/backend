package project.study.ranking.engine;

import static project.study.studysession.StudySessionThresholds.MIN_LIST_FOCUS_SEC;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
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
 */
@Component
@RequiredArgsConstructor
public class StandingsCalculator {

    /** 집중률 참가 조건 — 주간 순공 10시간, 월간 30시간 (명세 §1-1). */
    static final long WEEKLY_RATE_MIN_FOCUS_SEC = 36_000;

    static final long MONTHLY_RATE_MIN_FOCUS_SEC = 108_000;

    private final RankingSource source;

    /** 판 전체(onlyUserId=null) 또는 한 사용자의 줄. live는 asOf에 나눈 진행 중 조각이다. */
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
        for (LivePiece piece : live) {
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
