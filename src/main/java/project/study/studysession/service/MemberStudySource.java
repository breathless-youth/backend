package project.study.studysession.service;

import static project.study.studysession.StudySessionThresholds.MIN_LIST_FOCUS_SEC;

import java.sql.Connection;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import project.study.studysession.dto.LiveDraft;
import project.study.studysession.dto.LivePiece;
import project.study.studysession.dto.MemberStudy;
import project.study.studysession.dto.MemberStudy.Presence;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.repository.MemberStudyQueries;
import project.study.studysession.repository.MemberStudyQueries.FocusTotals;

/**
 * 그룹(BY-1124)이 멤버의 앱 전체 공부 상태·순공을 읽는 유일한 입구 — {@link RankingSource}와 같은 자리다. group 도메인이 세션 내부
 * 구조(리포지토리·분할기)를 직접 알지 않게 한다.
 *
 * <p>사람 집합 단위로 확정 합계·마지막 공부·draft·확정된 draft id를 한 번씩 읽는다(사람마다 쿼리하지 않는다). 확정은 세션 저장과 draft 삭제가 한
 * 트랜잭션이라, 확정 합계와 draft를 한 REPEATABLE READ 스냅샷에서 읽어야 그 사이 확정된 세션이 두 번 세지거나 빠지지 않는다 — 그래서 바깥
 * 트랜잭션이 다른 격리 수준이면 거절한다.
 */
@Service
@RequiredArgsConstructor
public class MemberStudySource {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final MemberStudyQueries queries;
    private final ActiveStudySessionRepository activeStudySessionRepository;
    private final ActiveStudySessionService activeStudySessionService;

    /** 모든 userId에 값이 있다 — 기록이 없으면 IDLE·0·0·null. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<Long, MemberStudy> of(Collection<Long> userIds, Instant asOf) {
        requireRepeatableRead();
        Set<Long> ids = Set.copyOf(userIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Week week = Week.of(asOf);
        Map<Long, FocusTotals> finalized = queries.focusTotals(ids, week.monday(), week.today());
        Map<Long, Instant> lastEnded = queries.lastMeasuredEndedAt(ids);
        Map<Long, List<LiveDraft>> drafts = openDrafts(ids, asOf);

        Map<Long, MemberStudy> studies = new HashMap<>();
        for (long userId : ids) {
            List<LiveDraft> mine = drafts.getOrDefault(userId, List.of());
            FocusTotals done = finalized.getOrDefault(userId, new FocusTotals(0, 0));
            LiveDraft latest = mine.stream()
                    .max(Comparator.comparing(LiveDraft::lastSeenAt))
                    .orElse(null);
            studies.put(
                    userId,
                    new MemberStudy(
                            userId,
                            presence(latest, asOf),
                            latest != null && latest.focusing(),
                            done.todaySec() + liveFocus(mine, week.today(), week.today()),
                            done.weekSec() + liveFocus(mine, week.monday(), week.today()),
                            later(lastEnded.get(userId), latest == null ? null : latest.lastSeenAt())));
        }
        return studies;
    }

    /** 이번 주 순공만 — 상태·마지막 공부는 계산하지 않는다(공개 그룹 평균용). 모든 userId에 값이 있다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<Long, Long> weekFocus(Collection<Long> userIds, Instant asOf) {
        requireRepeatableRead();
        Set<Long> ids = Set.copyOf(userIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Week week = Week.of(asOf);
        Map<Long, FocusTotals> finalized = queries.focusTotals(ids, week.monday(), week.today());
        Map<Long, List<LiveDraft>> drafts = openDrafts(ids, asOf);
        Map<Long, Long> weekFocus = new HashMap<>();
        for (long userId : ids) {
            long done = finalized.containsKey(userId) ? finalized.get(userId).weekSec() : 0;
            weekFocus.put(
                    userId, done + liveFocus(drafts.getOrDefault(userId, List.of()), week.monday(), week.today()));
        }
        return weekFocus;
    }

    /**
     * 확정되지 않은 draft만, 사람별로. 제출이 이미 확정됐는데 뒤늦은 하트비트로 되살아난 draft는 확정 합계가 대신 세고, 이미 끝난 세션이라 상태에도
     * 쓰지 않는다. draft가 없으면 확정 여부를 묻지 않는다.
     */
    private Map<Long, List<LiveDraft>> openDrafts(Set<Long> ids, Instant asOf) {
        List<LiveDraft> drafts = activeStudySessionService.liveDraftsOf(ids, asOf);
        if (drafts.isEmpty()) {
            return Map.of();
        }
        Set<Long> unfinalized = Set.copyOf(activeStudySessionRepository.findUnfinalizedIdsByUserIdIn(ids));
        return drafts.stream()
                .filter(draft -> unfinalized.contains(draft.draftId()))
                .collect(Collectors.groupingBy(LiveDraft::userId));
    }

    /** 진행 중 조각 중 날짜가 from~to이고 순공 1초 이상인 것의 합 — 랭킹이 진행 중 조각을 더하는 기준과 같다. */
    private static long liveFocus(List<LiveDraft> drafts, LocalDate from, LocalDate to) {
        long sum = 0;
        for (LiveDraft draft : drafts) {
            for (LivePiece piece : draft.pieces()) {
                if (piece.focusSec() >= MIN_LIST_FOCUS_SEC
                        && !piece.statDate().isBefore(from)
                        && !piece.statDate().isAfter(to)) {
                    sum += piece.focusSec();
                }
            }
        }
        return sum;
    }

    /**
     * 마지막 수신이 60초(랭킹의 집중 중 창)보다 오래됐거나 draft가 없으면 IDLE. 진행 중인 이벤트가 일시정지면 PAUSED, 그 밖(폰·자리 비움 등
     * 포함)은 측정이 이어지고 있어 STUDYING이다.
     */
    private static Presence presence(LiveDraft latest, Instant asOf) {
        if (latest == null || latest.lastSeenAt().isBefore(asOf.minus(ActiveStudySessionService.FOCUSING_WINDOW))) {
            return Presence.IDLE;
        }
        return latest.openEvent() == EventStatus.PAUSE ? Presence.PAUSED : Presence.STUDYING;
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    /**
     * 바깥 트랜잭션에 합류하면 그쪽 격리 수준이 이겨서 한 스냅샷 보장이 조용히 사라진다 — 기본 격리 수준의 바깥 트랜잭션 안에서 부르면 바로
     * 실패시킨다(StandingsCalculator와 같다).
     */
    private static void requireRepeatableRead() {
        Integer level = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (level == null || level != Connection.TRANSACTION_REPEATABLE_READ) {
            throw new IllegalStateException("멤버 공부 상태는 REPEATABLE_READ 트랜잭션에서만 읽는다(현재 격리 수준: " + level
                    + "). 바깥 트랜잭션이 다른 격리 수준이면 확정 합계와 draft를 한 스냅샷에서 읽는 보장이 사라진다");
        }
    }

    /** 오늘은 asOf의 KST 날짜, 이번 주는 그 주 월요일부터 — 기록 탭·랭킹 주간과 같은 경계다. */
    private record Week(LocalDate monday, LocalDate today) {

        static Week of(Instant asOf) {
            LocalDate today = LocalDate.ofInstant(asOf, KST);
            return new Week(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today);
        }
    }
}
