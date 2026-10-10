package project.study.studysession.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.studysession.StudySessionThresholds;
import project.study.studysession.dto.LivePiece;
import project.study.studysession.dto.RankingDaysRow;
import project.study.studysession.dto.RankingStreakRow;
import project.study.studysession.dto.RankingTotalRow;
import project.study.studysession.entity.TimeSlot;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.repository.StudySessionRankingQueries;
import project.study.studysession.repository.StudySessionRepository;

/**
 * 랭킹(BY-828)이 세션 데이터를 읽는 유일한 입구 — 집계 쿼리·진행 중 조각·스트릭을 한곳에 모아 ranking 도메인이 세션 내부
 * 구조(리포지토리·분할기)를 직접 알지 않게 한다. metrics의 StudySessionMetricsService와 같은 자리다.
 */
@Service
@RequiredArgsConstructor
public class RankingSource {

    private final StudySessionRankingQueries queries;
    private final StudySessionRepository studySessionRepository;
    private final ActiveStudySessionRepository activeStudySessionRepository;
    private final StudySessionService studySessionService;
    private final ActiveStudySessionService activeStudySessionService;

    public List<RankingTotalRow> periodTotals(LocalDate from, LocalDate to, Long userId) {
        return queries.periodTotals(from, to, userId);
    }

    public List<RankingTotalRow> slotTotals(TimeSlot slot, LocalDate from, LocalDate to, Long userId) {
        return queries.slotTotals(slot, from, to, userId);
    }

    public List<RankingDaysRow> studyDays(LocalDate today, Long userId) {
        return queries.studyDays(today, userId);
    }

    public List<RankingStreakRow> maxStreaks(LocalDate today, Long userId) {
        return queries.maxStreaks(today, userId);
    }

    public Map<Long, String> activeNicknames(Collection<Long> userIds) {
        return queries.activeNicknames(userIds);
    }

    public List<LivePiece> livePieces(Instant asOf) {
        return activeStudySessionService.livePieces(asOf);
    }

    /**
     * 지금 남아 있는 draft(active_study_session)의 id 전부. 캐시된 진행 중 조각 중 draftId가 여기 없는 것은 그 사이 확정·폐기된
     * 세션이다 — 확정은 세션 저장과 draft 삭제가 한 트랜잭션이라, 같은 스냅샷에서 확정 합계를 읽을 때 이 목록으로 걸러야 이중
     * 집계가 없다.
     */
    public Set<Long> openDraftIds() {
        return Set.copyOf(activeStudySessionRepository.findAllIds());
    }

    /** 지금 이어지는 연속 공부일 — 스트릭 API의 streak와 같은 값. */
    public int currentStreak(long userId) {
        return studySessionService.streak(userId, null, null).streak();
    }

    /** from~to 중 순공 1분 이상 조각이 있는 날 수 — 누적 일수의 최근 페이스 계산용. */
    @Transactional(readOnly = true)
    public int studiedDays(long userId, LocalDate from, LocalDate to) {
        return studySessionRepository
                .findDistinctStatDatesBetween(userId, from, to, StudySessionThresholds.MIN_LIST_FOCUS_SEC)
                .size();
    }
}
